package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.DictRef
import gg.netherite.world.nwf.DictionaryLookup
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.FrameCodec
import gg.netherite.world.nwf.FrameEncodeOptions
import gg.netherite.world.nwf.FramePipeline
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.IndexEntry
import gg.netherite.world.nwf.NwfDictionary
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.NwfFormatException
import gg.netherite.world.nwf.RootDraft
import gg.netherite.world.nwf.SegmentRef
import gg.netherite.world.nwf.WorldHeader
import gg.netherite.world.nwf.WrittenSegment
import gg.netherite.world.nwf.convert.BuiltWorld
import gg.netherite.world.nwf.convert.NwfWorldBuilder
import gg.netherite.world.nwf.convert.NwfWorldBuilderFactory
import gg.netherite.world.nwf.convert.WorldBuildOptions
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.TreeMap

/**
 * Builds generation-1 worlds: every frame inline while the frames plus the raw INDEX fit
 * [WorldBuildOptions.inlineBudgetBytes], otherwise every frame in sorted segments cut at
 * [WorldBuildOptions.segmentTargetBytes] and an empty INLINE.
 *
 * Each chunk is encoded as it arrives (light per [WorldBuildOptions.lightPolicy], heightmaps kept, the kind's
 * dictionary when one is given). Placement needs every frame, so the builder holds every compressed frame until
 * [NwfWorldBuilder.finish]: memory is the world's compressed size, capped by [WorldBuildOptions.maxHeldBytes] (a chunk
 * past the cap fails with LIMIT_EXCEEDED and spends the builder), and peaks near twice that while [NwfWorldBuilder.
 * finish] assembles the placed bytes. Uncompressed NBT is never held beyond one chunk. With a base, the root gets
 * HAS_BASE and the base's root SHA-256, its DICTS and HEADER dimensions start with the base's (the layout a world
 * created empty from a template has), and every live base key the source did not emit gets a TOMBSTONE, so the
 * world's effective content is exactly what the converter produced.
 */
class NwfV1WorldBuilderFactory(
    private val pipeline: FramePipeline,
    private val rootCodec: NwfV1RootCodec = NwfV1RootCodec(),
) : NwfWorldBuilderFactory {
    override fun create(options: WorldBuildOptions): NwfWorldBuilder = Builder(options)

    private inner class Builder(private val options: WorldBuildOptions) : NwfWorldBuilder {
        private var header: WorldHeader? = null
        private val frames = TreeMap<ChunkKey, EncodedFrame>()
        private var finished = false
        private var heldBytes = 0L

        /** This world's dictionaries in [ChunkKind] order. */
        private val kindDictionaries: List<Pair<ChunkKind, NwfDictionary>> =
            ChunkKind.entries.mapNotNull { kind -> options.dictionaries[kind]?.let { kind to it } }

        /**
         * DICTS: a base's dictionaries first, in the base's order, so base frames keep their slots in this root (a
         * reader decodes a base frame with the dictionary slot its base entry names); then the kinds' dictionaries
         * the base does not hold, in [ChunkKind] order. Without a base this is the kinds' dictionaries in order.
         */
        private val baseDictionaries: List<DictRef> = options.base?.dictionaries.orEmpty()
        private val ownDictionaries: List<Pair<ChunkKind, NwfDictionary>> =
            kindDictionaries.filter { (_, d) -> baseDictionaries.none { it.sha256 == d.sha256 } }
        private val dictionaryRefs: List<DictRef> =
            baseDictionaries + ownDictionaries.map { (_, d) -> DictRef(d.zstdDictId, d.sha256) }

        /** Each kind's DICTS slot. */
        private val kindSlots: Map<ChunkKind, Int> = kindDictionaries.associate { (kind, d) ->
            val inBase = baseDictionaries.indexOfFirst { it.sha256 == d.sha256 }
            val own = baseDictionaries.size + ownDictionaries.indexOfFirst { it.first == kind }
            val slot = if (inBase >= 0) inBase else own
            kind to slot
        }

        /**
         * HEADER dimensions: a base's first, in the base's order, so base keys keep their dimension index (tombstones,
         * store fall-through); then the converter's dimensions the base lacks. [dimensionMap] maps a converter
         * dimension index to its index here.
         */
        private var dimensions: List<String> = emptyList()
        private var dimensionMap: IntArray = IntArray(0)

        init {
            require(options.inlineBudgetBytes >= 0) { "inlineBudgetBytes ${options.inlineBudgetBytes}" }
            require(options.segmentTargetBytes > 0) { "segmentTargetBytes ${options.segmentTargetBytes}" }
            require(options.maxHeldBytes > 0) { "maxHeldBytes ${options.maxHeldBytes}" }
            require(options.packInlineMaxRawBytes >= 0) { "packInlineMaxRawBytes ${options.packInlineMaxRawBytes}" }
            options.base?.let { base ->
                if (base.hasBase) throw NwfFormatException(NwfFormatError.NESTED_BASE, "a base must not have a base")
                if (!base.isTemplate) {
                    throw NwfFormatException(NwfFormatError.DANGLING_REFERENCE, "a base must be a TEMPLATE root")
                }
            }
        }

        override fun header(header: WorldHeader) {
            check(!finished) { "builder already finished or spent" }
            check(this.header == null) { "header given twice" }
            this.header = header
            val baseDimensions = options.base?.header?.dimensions
            dimensions = if (baseDimensions == null) {
                header.dimensions
            } else {
                baseDimensions + header.dimensions.filter { it !in baseDimensions }.distinct()
            }
            dimensionMap = IntArray(header.dimensions.size) { dimensions.indexOf(header.dimensions[it]) }
            require(dimensions.size <= ChunkKey.MAX_DIM + 1) { "${dimensions.size} dimensions with the base's" }
        }

        override fun chunk(key: ChunkKey, nbt: ByteBuffer) {
            check(!finished) { "builder already finished or spent" }
            val h = checkNotNull(header) { "header must come before the first chunk" }
            require(key.dim < h.dimensions.size) { "$key: HEADER has ${h.dimensions.size} dimensions" }
            val placed = key.copy(dim = dimensionMap[key.dim])
            require(!frames.containsKey(placed)) { "$key given twice" }
            val slot = kindSlots[key.kind]
            val encodeOptions = FrameEncodeOptions(
                level = options.saveLevel,
                lightPolicy = options.lightPolicy,
                heightmapPolicy = HeightmapPolicy.KEEP,
                dictionary = options.dictionaries[key.kind].takeIf { slot != null },
                dictSlot = slot ?: NwfFormat.NONE_U16,
            )
            val frame = pipeline.encode(placed, nbt, encodeOptions)
            val held = heldBytes + frame.header.storedLength
            if (held > options.maxHeldBytes) {
                finished = true // spent: nothing partial can be finished into a world
                frames.clear()
                nwfError(
                    NwfFormatError.LIMIT_EXCEEDED,
                    "$key: the world's frames exceed maxHeldBytes ${options.maxHeldBytes} (at $held bytes)",
                )
            }
            heldBytes = held
            frames[placed] = frame
        }

        override fun finish(): BuiltWorld {
            check(!finished) { "builder already finished or spent" }
            finished = true
            val source = checkNotNull(header) { "no header given" }
            val tombstones = baseTombstones()
            val indexBytes = (frames.size + tombstones.size).toLong() * NwfFormat.INDEX_ENTRY_BYTES
            val frameBytes = frames.values.sumOf { it.header.storedLength.toLong() }
            val rawBytes = frames.values.sumOf { it.header.rawLength.toLong() }
            val packed = options.packInlineMaxRawBytes > 0 && rawBytes <= options.packInlineMaxRawBytes &&
                rawBytes + indexBytes <= options.inlineBudgetBytes
            val placed = when {
                packed -> placeInline(rawFrames())
                frameBytes + indexBytes <= options.inlineBudgetBytes -> placeInline(frames.values)
                else -> placeSegmented()
            }
            val entries = (placed.entries + tombstones).sortedBy { it.key }
            val base = options.base
            val draft = RootDraft(
                flags = if (base != null) NwfFormat.ROOT_FLAG_HAS_BASE else 0,
                generation = 1,
                header = buildHeader(source),
                dictionaries = dictionaryRefs,
                segments = placed.segments.map { SegmentRef(it.sha256, 0, it.length, it.frameOffsets.size) },
                entries = entries,
                inline = ByteBuffer.wrap(placed.inline),
                packInlineLevel = options.packInlineLevel.takeIf { packed },
                headerLevel = options.saveLevel,
            )
            rootCodec.write(draft) // fails closed on any limit or reference breach before the caller PUTs anything
            return BuiltWorld(draft, placed.segments)
        }

        /**
         * Every held frame decoded back to its stored NBT as a RAW frame (codec RAW, no dictionary, CRC32C over the raw
         * payload), flags and DataVersion kept: the frames of a packed root. The frames were encoded by this builder,
         * so their dictionaries are this world's kind dictionaries.
         */
        private fun rawFrames(): List<EncodedFrame> {
            val bySlot = kindSlots.entries.associate { (kind, slot) -> slot to options.dictionaries.getValue(kind) }
            val lookup = DictionaryLookup { slot -> bySlot[slot] }
            return frames.values.map { frame ->
                if (frame.header.codec == FrameCodec.RAW) return@map frame
                val raw = pipeline.decode(frame, lookup, null).copyRemaining()
                val h = frame.header
                EncodedFrame(
                    h.copy(
                        codec = FrameCodec.RAW,
                        rawLength = raw.size,
                        storedLength = raw.size,
                        crc32c = crc32c(raw),
                        dictSlot = NwfFormat.NONE_U16,
                    ),
                    frame.dataVersion,
                    ByteBuffer.wrap(raw),
                )
            }
        }

        private fun placeInline(placedFrames: Collection<EncodedFrame>): Placement {
            val inline = ByteArrayOutputStream()
            val entries = placedFrames.map { frame ->
                val offset = inline.size()
                inline.write(frame.payloadView().copyRemaining())
                entryOf(frame, NwfFormat.NONE_U16, offset)
            }
            return Placement(entries, emptyList(), inline.toByteArray())
        }

        private fun placeSegmented(): Placement {
            val groups = ArrayList<List<EncodedFrame>>()
            var current = ArrayList<EncodedFrame>()
            var currentBytes = 0L
            for (frame in frames.values) {
                val size = NwfFormat.FRAME_HEADER_BYTES.toLong() + frame.header.storedLength
                if (current.isNotEmpty() && currentBytes + size > options.segmentTargetBytes) {
                    groups += current
                    current = ArrayList()
                    currentBytes = 0
                }
                current += frame
                currentBytes += size
            }
            if (current.isNotEmpty()) groups += current
            val segments = groups.map { NwfV1SegmentCodec.write(it) }
            val entries = groups.flatMapIndexed { i, group ->
                group.map { frame -> entryOf(frame, i, segments[i].frameOffsets.getValue(frame.key)) }
            }
            return Placement(entries, segments, ByteArray(0))
        }

        private fun entryOf(frame: EncodedFrame, location: Int, offset: Int): IndexEntry {
            val h = frame.header
            return IndexEntry(
                x = h.x,
                z = h.z,
                dim = h.dim,
                kind = h.kind,
                codec = h.codec,
                flags = h.flags,
                location = location,
                dictSlot = h.dictSlot,
                offset = offset,
                storedLength = h.storedLength,
                rawLength = h.rawLength,
                crc32c = h.crc32c,
                dataVersion = frame.dataVersion,
            )
        }

        private fun baseTombstones(): List<IndexEntry> {
            val base = options.base ?: return emptyList()
            return base.entries()
                .filter { !it.isTombstone && !frames.containsKey(it.key) }
                .map {
                    IndexEntry(
                        x = it.x,
                        z = it.z,
                        dim = it.dim,
                        kind = it.kind,
                        codec = FrameCodec.RAW,
                        flags = EntryFlags.TOMBSTONE,
                        location = NwfFormat.NONE_U16,
                        dictSlot = NwfFormat.NONE_U16,
                        offset = 0,
                        storedLength = 0,
                        rawLength = 0,
                        crc32c = 0,
                        dataVersion = 0,
                    )
                }
                .toList()
        }

        /** The converter's header with the builder's placement fields; every other field (host ones too) as given. */
        private fun buildHeader(source: WorldHeader): WorldHeader = source.copy(
            contentClass = options.contentClass,
            parentRootSha = ByteArray(0),
            dimensions = dimensions,
            baseRootSha = options.base?.rootSha?.bytes() ?: ByteArray(0),
            lightPolicy = options.lightPolicy,
            heightmapPolicy = HeightmapPolicy.KEEP,
            segmentPrefix = options.segmentPrefix,
        )
    }
}

/** Where a builder put the frames. */
private class Placement(val entries: List<IndexEntry>, val segments: List<WrittenSegment>, val inline: ByteArray)
