package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.DictRef
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.FrameCodec
import gg.netherite.world.nwf.IndexEntry
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.NwfRootCodec
import gg.netherite.world.nwf.RootDraft
import gg.netherite.world.nwf.RootView
import gg.netherite.world.nwf.SectionType
import gg.netherite.world.nwf.SegmentRef
import gg.netherite.world.nwf.Sha256
import gg.netherite.world.nwf.TocEntry
import gg.netherite.world.nwf.WorldHeader
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Root writer settings. [sectionLevel] compresses INDEX (always zstd, no dictionary). Packed inline (off by
 * default) compresses the whole INLINE body as one zstd stream at [packInlineLevel] when its raw length is at most
 * [packInlineMaxRawBytes] (0 = off) and packing makes it smaller; frames of a packed root are normally RAW.
 */
data class RootCodecOptions(
    val sectionLevel: Int = DEFAULT_SECTION_LEVEL,
    val packInlineMaxRawBytes: Long = 0,
    val packInlineLevel: Int = DEFAULT_PACK_LEVEL,
) {
    init {
        require(sectionLevel in ZstdSupport.levels) { "sectionLevel $sectionLevel outside ${ZstdSupport.levels}" }
        require(packInlineLevel in ZstdSupport.levels) {
            "packInlineLevel $packInlineLevel outside ${ZstdSupport.levels}"
        }
        require(packInlineMaxRawBytes >= 0) { "packInlineMaxRawBytes must not be negative" }
    }

    companion object {
        const val DEFAULT_SECTION_LEVEL: Int = 3
        const val DEFAULT_PACK_LEVEL: Int = 19

        /** The suggested packing bound when packed inline is enabled: raw INLINE of at most 256 KiB. */
        const val PACK_INLINE_FLIP_RAW_BYTES: Long = 256L * 1024
    }
}

/**
 * NWF v1 root reader and writer. Stateless and thread-safe.
 *
 * The reader verifies everything before a [RootView] exists: magic, major, `headerCrc`, reserved fields, known flags,
 * TOC contiguity (bodies in TOC order, no gaps, ending exactly at the object end), every section CRC, required
 * sections, the HEADER protobuf, base and dictionary/segment references, inline and segment bounds of every entry, and
 * strict INDEX order. The one per-chunk failure is an unknown codec id (fail closed for that chunk): the root
 * parses, and materialising that entry ([RootView.find], [RootView.entries]) throws [NwfFormatError.UNKNOWN_CODEC].
 */
class NwfV1RootCodec(private val options: RootCodecOptions = RootCodecOptions()) : NwfRootCodec {

    override fun parse(bytes: ByteBuffer): NwfV1RootView {
        val copy = bytes.copyRemaining()
        return failClosed("root") { RootParser(copy).parse() }
    }

    override fun write(draft: RootDraft): ByteArray = RootWriter(draft, options).write()

    companion object {
        /** The HEADER section body for [header]. */
        fun encodeHeader(header: WorldHeader): ByteArray = NwfHeaderProto.encode(header)

        /** Parses a HEADER section body. Throws [gg.netherite.world.nwf.NwfFormatException] when it does not parse. */
        fun decodeHeader(bytes: ByteArray): WorldHeader = failClosed("HEADER") { NwfHeaderProto.decode(bytes) }
    }
}

// ---- shared layout ---------------------------------------------------------------------------------------------

internal object RootLayout {
    const val OFF_MAJOR = 4
    const val OFF_MINOR = 6
    const val OFF_FLAGS = 8
    const val OFF_SECTION_COUNT = 12
    const val OFF_GENERATION = 16
    const val OFF_HEADER_CRC = 24
    const val OFF_RESERVED = 28

    const val TOC_FLAGS_KNOWN = NwfFormat.TOC_FLAG_OPTIONAL or NwfFormat.TOC_FLAG_ZSTD or NwfFormat.TOC_FLAG_DICT

    /** INDEX body: u32 count, then 36-byte entries. */
    const val INDEX_PREFIX = 4

    /** DICTS / SEGMENTS body: u16 count, then fixed-size entries. */
    const val LIST_PREFIX = 2

    /** Entry field offsets inside a 36-byte INDEX entry. */
    const val E_X = 0
    const val E_Z = 4
    const val E_DIM = 8
    const val E_KIND = 9
    const val E_CODEC = 10
    const val E_FLAGS = 11
    const val E_LOCATION = 12
    const val E_DICT = 14
    const val E_OFFSET = 16
    const val E_STORED = 20
    const val E_RAW = 24
    const val E_CRC = 28
    const val E_DATA_VERSION = 32

    private const val REGION_SHIFT = 5

    /** INDEX order `(dim, kind, x>>5, z>>5, x, z)` on raw fields, identical to [ChunkKey] natural order. */
    fun compareKeys(dimA: Int, kindA: Int, xA: Int, zA: Int, dimB: Int, kindB: Int, xB: Int, zB: Int): Int {
        var c = dimA.compareTo(dimB)
        if (c == 0) c = kindA.compareTo(kindB)
        if (c == 0) c = (xA shr REGION_SHIFT).compareTo(xB shr REGION_SHIFT)
        if (c == 0) c = (zA shr REGION_SHIFT).compareTo(zB shr REGION_SHIFT)
        if (c == 0) c = xA.compareTo(xB)
        if (c == 0) c = zA.compareTo(zB)
        return c
    }
}

/** What an INDEX entry may reference; one instance per root, used by the reader and the writer alike. */
internal class EntryRules(
    private val dimensionCount: Int,
    private val dictionaryCount: Int,
    private val segments: List<SegmentRef>,
    private val inlineLength: Long,
    private val hasBase: Boolean,
) {
    /** Structural checks of one entry. u32 fields arrive as unsigned [Long]s. */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    fun check(e: RawEntry, where: String) {
        if (e.dim >= dimensionCount) {
            nwfError(NwfFormatError.DANGLING_REFERENCE, "$where: dim ${e.dim}, HEADER has $dimensionCount")
        }
        if (ChunkKind.fromId(e.kind) == null) nwfError(NwfFormatError.OUT_OF_BOUNDS, "$where: kind ${e.kind}")
        if (e.flags and EntryFlags.KNOWN.inv() != 0) nwfError(NwfFormatError.UNKNOWN_FLAGS, "$where: flags ${e.flags}")
        if (e.offset > Int.MAX_VALUE || e.stored > Int.MAX_VALUE) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "$where: offset ${e.offset} / storedLength ${e.stored}")
        }
        if (e.raw > NwfFormat.MAX_FRAME_RAW_BYTES) nwfError(NwfFormatError.LIMIT_EXCEEDED, "$where: rawLength ${e.raw}")
        if (e.flags and EntryFlags.TOMBSTONE != 0) {
            checkTombstone(e, where)
            return
        }
        if (e.dictSlot != NwfFormat.NONE_U16 && e.dictSlot >= dictionaryCount) {
            nwfError(NwfFormatError.DANGLING_REFERENCE, "$where: dictSlot ${e.dictSlot} of $dictionaryCount")
        }
        FrameCodec.fromId(e.codec)?.let { codec -> checkCodec(codec, e, where) }
        if (e.location == NwfFormat.NONE_U16) {
            if (e.offset + e.stored > inlineLength) {
                nwfError(
                    NwfFormatError.OUT_OF_BOUNDS,
                    "$where: inline [${e.offset}, +${e.stored}) outside INLINE of $inlineLength bytes",
                )
            }
        } else {
            val segment = segments.getOrNull(e.location)
                ?: nwfError(NwfFormatError.DANGLING_REFERENCE, "$where: segment ${e.location} of ${segments.size}")
            val end = e.offset + NwfFormat.FRAME_HEADER_BYTES + e.stored
            val frameArea = segment.length - NwfFormat.SEGMENT_TRAILER_BYTES
            if (e.offset < NwfFormat.SEGMENT_HEADER_BYTES || end > frameArea) {
                nwfError(
                    NwfFormatError.OUT_OF_BOUNDS,
                    "$where: frame [${e.offset}, $end) outside segment ${e.location} of ${segment.length} bytes",
                )
            }
        }
    }

    private fun checkCodec(codec: FrameCodec, e: RawEntry, where: String) {
        if ((codec == FrameCodec.ZSTD_DICT) != (e.dictSlot != NwfFormat.NONE_U16)) {
            nwfError(NwfFormatError.DANGLING_REFERENCE, "$where: codec $codec with dictSlot ${e.dictSlot}")
        }
        val deltaFlag = e.flags and EntryFlags.BASE_DELTA != 0
        if ((codec == FrameCodec.ZSTD_BASE_DELTA) != deltaFlag) {
            nwfError(NwfFormatError.UNKNOWN_FLAGS, "$where: codec $codec with BASE_DELTA flag $deltaFlag")
        }
        if (codec == FrameCodec.ZSTD_BASE_DELTA && !hasBase) {
            nwfError(NwfFormatError.DANGLING_REFERENCE, "$where: base-delta frame in a root without HAS_BASE")
        }
        if (codec == FrameCodec.RAW && e.raw != e.stored) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "$where: RAW stores ${e.stored} bytes for rawLength ${e.raw}")
        }
    }

    /** A tombstone has no payload: RAW, inline, no dictionary, all lengths, offset and CRC zero, no other flag. */
    private fun checkTombstone(e: RawEntry, where: String) {
        val shapeOk = e.flags == EntryFlags.TOMBSTONE && e.codec == FrameCodec.RAW.id &&
            e.location == NwfFormat.NONE_U16 && e.dictSlot == NwfFormat.NONE_U16
        if (!shapeOk) nwfError(NwfFormatError.UNKNOWN_FLAGS, "$where: malformed tombstone")
        if (e.offset != 0L || e.stored != 0L || e.raw != 0L || e.crc != 0) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "$where: tombstone with payload fields")
        }
    }
}

/** One INDEX entry as stored, before enum mapping. */
internal class RawEntry(
    val x: Int,
    val z: Int,
    val dim: Int,
    val kind: Int,
    val codec: Int,
    val flags: Int,
    val location: Int,
    val dictSlot: Int,
    val offset: Long,
    val stored: Long,
    val raw: Long,
    val crc: Int,
    val dataVersion: Int,
) {
    fun compareTo(o: RawEntry): Int = RootLayout.compareKeys(dim, kind, x, z, o.dim, o.kind, o.x, o.z)

    companion object {
        fun at(index: ByteBuffer, base: Int): RawEntry = RawEntry(
            x = index.getInt(base + RootLayout.E_X),
            z = index.getInt(base + RootLayout.E_Z),
            dim = index.u8(base + RootLayout.E_DIM),
            kind = index.u8(base + RootLayout.E_KIND),
            codec = index.u8(base + RootLayout.E_CODEC),
            flags = index.u8(base + RootLayout.E_FLAGS),
            location = index.u16(base + RootLayout.E_LOCATION),
            dictSlot = index.u16(base + RootLayout.E_DICT),
            offset = index.u32(base + RootLayout.E_OFFSET),
            stored = index.u32(base + RootLayout.E_STORED),
            raw = index.u32(base + RootLayout.E_RAW),
            crc = index.getInt(base + RootLayout.E_CRC),
            dataVersion = index.getInt(base + RootLayout.E_DATA_VERSION),
        )

        fun of(e: IndexEntry): RawEntry = RawEntry(
            x = e.x,
            z = e.z,
            dim = e.dim,
            kind = e.kind.id,
            codec = e.codec.id,
            flags = e.flags,
            location = e.location,
            dictSlot = e.dictSlot,
            offset = e.offset.toLong() and U32_MASK,
            stored = e.storedLength.toLong() and U32_MASK,
            raw = e.rawLength.toLong() and U32_MASK,
            crc = e.crc32c,
            dataVersion = e.dataVersion,
        )
    }
}

internal fun checkBaseFlags(flags: Int, header: WorldHeader) {
    val template = flags and NwfFormat.ROOT_FLAG_TEMPLATE != 0
    val hasBase = flags and NwfFormat.ROOT_FLAG_HAS_BASE != 0
    if (template && hasBase) nwfError(NwfFormatError.NESTED_BASE, "a TEMPLATE root must not have a base")
    if (hasBase != (header.baseRootSha.size == Sha256.BYTES)) {
        nwfError(
            NwfFormatError.DANGLING_REFERENCE,
            "HAS_BASE=$hasBase with a ${header.baseRootSha.size}-byte baseRootSha",
        )
    }
    if (header.dimensions.size > ChunkKey.MAX_DIM + 1) {
        nwfError(NwfFormatError.INVALID_HEADER, "${header.dimensions.size} dimensions, at most ${ChunkKey.MAX_DIM + 1}")
    }
}

// ---- reader ----------------------------------------------------------------------------------------------------

private class RootParser(private val bytes: ByteArray) {
    private val b = bytes.littleEndian()

    fun parse(): NwfV1RootView {
        val sectionCount = checkFixedHeader()
        val toc = readToc(sectionCount)
        val sections = HashMap<SectionType, Pair<TocEntry, ByteArray>>()
        for ((i, entry) in toc.withIndex()) {
            val crc = crc32c(bytes, entry.offset.toInt(), entry.storedLength.toInt())
            if (crc != entry.crc32c) nwfError(NwfFormatError.CRC_MISMATCH, "section $i (type ${entry.type}) CRC")
            val type = SectionType.fromId(entry.type)
            if (type == null) {
                // Forward compatibility: an unknown OPTIONAL section is skipped whatever its flag or dict bits say (a
                // later minor may define them); an unknown required section is refused.
                if (!entry.isOptional) {
                    nwfError(NwfFormatError.UNKNOWN_REQUIRED_SECTION, "section $i has unknown type ${entry.type}")
                }
                continue
            }
            checkTocFields(i, entry)
            if (sections.containsKey(type)) nwfError(NwfFormatError.BAD_SECTIONS, "section $type repeated")
            sections[type] = entry to body(type, entry)
        }
        for (required in SectionType.entries.filter { it.required }) {
            if (!sections.containsKey(required)) nwfError(NwfFormatError.BAD_SECTIONS, "required $required missing")
        }
        val flags = b.getInt(RootLayout.OFF_FLAGS)
        val header = NwfV1RootCodec.decodeHeader(sections.getValue(SectionType.HEADER).second)
        checkBaseFlags(flags, header)
        val dictionaries = sections[SectionType.DICTS]?.let { readDicts(it.second) } ?: emptyList()
        val segments = sections[SectionType.SEGMENTS]?.let { readSegments(it.second) } ?: emptyList()
        val inline = sections[SectionType.INLINE]?.second ?: ByteArray(0)
        val index = sections.getValue(SectionType.INDEX).second
        val count = checkIndex(index, header, dictionaries, segments, inline.size.toLong(), flags)
        return NwfV1RootView(
            minor = b.u16(RootLayout.OFF_MINOR),
            flags = flags,
            generation = b.getLong(RootLayout.OFF_GENERATION),
            header = header,
            dictionaries = dictionaries,
            segments = segments,
            entryCount = count,
            rootSha = sha256(bytes),
            storedLength = bytes.size.toLong(),
            index = ByteBuffer.wrap(index).asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN),
            inline = ByteBuffer.wrap(inline).asReadOnlyBuffer(),
            toc = toc,
            stats = sections[SectionType.STATS]?.second,
        )
    }

    /** Magic, major, size limit, section count, `headerCrc`, flags, reserved and generation. Returns the count. */
    private fun checkFixedHeader(): Int {
        if (bytes.size < NwfFormat.SNIFF_LENGTH) nwfError(NwfFormatError.TRUNCATED, "${bytes.size} bytes")
        if (!NwfFormat.isRoot(bytes)) nwfError(NwfFormatError.BAD_MAGIC, "not an NWFR root")
        if (bytes.size < NwfFormat.ROOT_HEADER_BYTES) nwfError(NwfFormatError.TRUNCATED, "${bytes.size} bytes")
        val major = b.u16(RootLayout.OFF_MAJOR)
        if (major != NwfFormat.MAJOR) nwfError(NwfFormatError.UNSUPPORTED_MAJOR, "major $major")
        if (bytes.size > NwfFormat.MAX_ROOT_BYTES) nwfError(NwfFormatError.LIMIT_EXCEEDED, "root ${bytes.size} B")
        val count = b.u32(RootLayout.OFF_SECTION_COUNT)
        if (count < NwfFormat.MIN_SECTIONS || count > NwfFormat.MAX_SECTIONS) {
            nwfError(NwfFormatError.BAD_SECTIONS, "sectionCount $count")
        }
        val tocEnd = NwfFormat.ROOT_HEADER_BYTES + count.toInt() * NwfFormat.TOC_ENTRY_BYTES
        if (tocEnd > bytes.size) nwfError(NwfFormatError.TRUNCATED, "TOC ends at $tocEnd of ${bytes.size}")
        val crc = java.util.zip.CRC32C()
        crc.update(bytes, 0, RootLayout.OFF_HEADER_CRC)
        crc.update(bytes, NwfFormat.ROOT_HEADER_BYTES, tocEnd - NwfFormat.ROOT_HEADER_BYTES)
        if (crc.value.toInt() != b.getInt(RootLayout.OFF_HEADER_CRC)) {
            nwfError(NwfFormatError.CRC_MISMATCH, "headerCrc")
        }
        if (b.getInt(RootLayout.OFF_RESERVED) != 0) nwfError(NwfFormatError.UNKNOWN_FLAGS, "reserved header field")
        val flags = b.getInt(RootLayout.OFF_FLAGS)
        if (flags and NwfFormat.ROOT_FLAGS_KNOWN.inv() != 0) nwfError(NwfFormatError.UNKNOWN_FLAGS, "root flags $flags")
        if (b.getLong(RootLayout.OFF_GENERATION) < 1) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "generation ${b.getLong(RootLayout.OFF_GENERATION)}")
        }
        return count.toInt()
    }

    private fun readToc(count: Int): List<TocEntry> {
        var expected = (NwfFormat.ROOT_HEADER_BYTES + count * NwfFormat.TOC_ENTRY_BYTES).toLong()
        val toc = ArrayList<TocEntry>(count)
        for (i in 0 until count) {
            val at = NwfFormat.ROOT_HEADER_BYTES + i * NwfFormat.TOC_ENTRY_BYTES
            val offset = b.getLong(at + 16)
            val stored = b.getLong(at + 24)
            val raw = b.u32(at + 12)
            if (offset != expected) nwfError(NwfFormatError.OUT_OF_BOUNDS, "section $i at $offset, expected $expected")
            if (stored < 0 || stored > bytes.size - offset) {
                nwfError(NwfFormatError.TRUNCATED, "section $i [$offset, +$stored) beyond ${bytes.size} bytes")
            }
            if (b.u16(at + 6) != 0) nwfError(NwfFormatError.UNKNOWN_FLAGS, "section $i reserved field")
            val entry = TocEntry(
                type = b.u16(at),
                flags = b.u16(at + 2),
                dictSlot = b.u16(at + 4),
                crc32c = b.getInt(at + 8),
                rawLength = raw,
                offset = offset,
                storedLength = stored,
            )
            if (entry.decoded() && raw > NwfFormat.MAX_FRAME_RAW_BYTES) {
                nwfError(NwfFormatError.LIMIT_EXCEEDED, "section $i raw $raw")
            }
            toc += entry
            expected = offset + stored
        }
        if (expected != bytes.size.toLong()) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "sections end at $expected, object has ${bytes.size} bytes")
        }
        // Checked before any body is decompressed, so a small root cannot make the reader allocate more. Only sections
        // the reader decodes count: an unknown OPTIONAL section is skipped whole, whatever rawLength it declares.
        val rawTotal = toc.filter { it.decoded() }.sumOf { it.rawLength }
        if (rawTotal > NwfFormat.MAX_ROOT_RAW_BYTES) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "sections decode to $rawTotal bytes in all")
        }
        return toc
    }

    /** True unless the section is of unknown type with OPTIONAL set, which the reader skips without decoding. */
    private fun TocEntry.decoded(): Boolean = SectionType.fromId(type) != null || !isOptional

    /** TOC flags and dictSlot rules (SPEC 3.2) for a KNOWN section; unknown optional sections are never checked. */
    private fun checkTocFields(i: Int, entry: TocEntry) {
        if (entry.flags and RootLayout.TOC_FLAGS_KNOWN.inv() != 0) {
            nwfError(NwfFormatError.UNKNOWN_FLAGS, "section $i (type ${entry.type}) flags ${entry.flags}")
        }
        if (entry.flags and NwfFormat.TOC_FLAG_DICT != 0 || entry.dictSlot != NwfFormat.NONE_U16) {
            nwfError(
                NwfFormatError.BAD_SECTIONS,
                "section $i (type ${entry.type}): dictionary-compressed sections are not used in v1",
            )
        }
    }

    private fun body(type: SectionType, entry: TocEntry): ByteArray {
        val stored = bytes.copyOfRange(entry.offset.toInt(), (entry.offset + entry.storedLength).toInt())
        if (entry.flags and NwfFormat.TOC_FLAG_ZSTD == 0) {
            if (entry.rawLength != entry.storedLength) {
                nwfError(NwfFormatError.OUT_OF_BOUNDS, "section $type rawLength ${entry.rawLength} != stored")
            }
            return stored
        }
        // Within MAX_FRAME_RAW_BYTES (checked in readToc), so it fits an Int.
        return ZstdSupport.decompress(stored, entry.rawLength.toInt(), "section $type")
    }

    private fun readDicts(body: ByteArray): List<DictRef> {
        val buf = body.littleEndian()
        val count = listCount(buf, NwfFormat.DICT_ENTRY_BYTES, "DICTS")
        return List(count) { i ->
            val at = RootLayout.LIST_PREFIX + i * NwfFormat.DICT_ENTRY_BYTES
            DictRef(buf.getInt(at), Sha256(body.copyOfRange(at + 4, at + 4 + Sha256.BYTES)))
        }
    }

    private fun readSegments(body: ByteArray): List<SegmentRef> {
        val buf = body.littleEndian()
        val count = listCount(buf, NwfFormat.SEGMENT_ENTRY_BYTES, "SEGMENTS")
        return List(count) { i ->
            val at = RootLayout.LIST_PREFIX + i * NwfFormat.SEGMENT_ENTRY_BYTES
            val sha = Sha256(body.copyOfRange(at, at + Sha256.BYTES))
            val length = buf.getLong(at + 36)
            val minimum = NwfFormat.SEGMENT_HEADER_BYTES + NwfFormat.SEGMENT_TRAILER_BYTES
            if (length < minimum || length > NwfFormat.MAX_SEGMENT_BYTES) {
                nwfError(NwfFormatError.LIMIT_EXCEEDED, "segment $i length $length")
            }
            val frames = buf.u32(at + 44)
            if (frames > maxFramesIn(length)) {
                nwfError(NwfFormatError.OUT_OF_BOUNDS, "segment $i frameCount $frames in $length bytes")
            }
            SegmentRef(sha, buf.getInt(at + 32), length, frames.toInt())
        }
    }

    private fun listCount(buf: ByteBuffer, entryBytes: Int, name: String): Int {
        if (buf.limit() < RootLayout.LIST_PREFIX) nwfError(NwfFormatError.TRUNCATED, "$name body")
        val count = buf.u16(0)
        if (buf.limit() != RootLayout.LIST_PREFIX + count * entryBytes) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "$name body of ${buf.limit()} bytes for $count entries")
        }
        return count
    }

    /** Validates every INDEX entry and the strict order; returns the entry count. */
    @Suppress("LongParameterList")
    private fun checkIndex(
        index: ByteArray,
        header: WorldHeader,
        dicts: List<DictRef>,
        segments: List<SegmentRef>,
        inlineLength: Long,
        flags: Int,
    ): Int {
        val buf = index.littleEndian()
        if (index.size < RootLayout.INDEX_PREFIX) nwfError(NwfFormatError.TRUNCATED, "INDEX body")
        val count = buf.u32(0)
        if (index.size.toLong() != RootLayout.INDEX_PREFIX + count * NwfFormat.INDEX_ENTRY_BYTES) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "INDEX body of ${index.size} bytes for $count entries")
        }
        val rules = EntryRules(
            header.dimensions.size,
            dicts.size,
            segments,
            inlineLength,
            flags and NwfFormat.ROOT_FLAG_HAS_BASE != 0,
        )
        var previous: RawEntry? = null
        for (i in 0 until count.toInt()) {
            val entry = RawEntry.at(buf, RootLayout.INDEX_PREFIX + i * NwfFormat.INDEX_ENTRY_BYTES)
            rules.check(entry, "INDEX entry $i")
            if (previous != null && previous.compareTo(entry) >= 0) {
                nwfError(NwfFormatError.UNSORTED_INDEX, "INDEX entry $i does not follow entry ${i - 1}")
            }
            previous = entry
        }
        return count.toInt()
    }
}

/** The parsed, verified root. Holds the decompressed INDEX and INLINE bodies; entries materialise on access. */
class NwfV1RootView internal constructor(
    override val minor: Int,
    override val flags: Int,
    override val generation: Long,
    override val header: WorldHeader,
    override val dictionaries: List<DictRef>,
    override val segments: List<SegmentRef>,
    override val entryCount: Int,
    override val rootSha: Sha256,
    override val storedLength: Long,
    private val index: ByteBuffer,
    private val inline: ByteBuffer,
    /** The TOC as stored, unknown optional sections included. */
    val toc: List<TocEntry>,
    private val stats: ByteArray?,
) : RootView {
    override val inlineLength: Long get() = inline.capacity().toLong()

    /** The STATS protobuf, or null when the root has none. */
    fun stats(): ByteArray? = stats?.copyOf()

    override fun find(key: ChunkKey): IndexEntry? {
        val i = search(key.dim, key.kind.id, key.x, key.z)
        return if (i < 0) null else entryAt(i)
    }

    override fun entries(): Sequence<IndexEntry> = (0 until entryCount).asSequence().map { entryAt(it) }

    override fun hasNoEntries(dim: Int, x: Int, z: Int): Boolean =
        ChunkKind.entries.none { search(dim, it.id, x, z) >= 0 }

    override fun inlinePayload(entry: IndexEntry): ByteBuffer {
        if (!entry.isInline || entry.isTombstone) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "entry ${entry.key} is not an inline frame")
        }
        val start = entry.offset.toLong() and U32_MASK
        val end = start + (entry.storedLength.toLong() and U32_MASK)
        if (end > inline.capacity()) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "entry ${entry.key} [$start, $end) outside INLINE")
        }
        return inline.duplicate().position(start.toInt()).limit(end.toInt()).slice().asReadOnlyBuffer()
    }

    /** The entry at INDEX position [i]. An unknown codec id fails closed for that chunk only. */
    fun entryAt(i: Int): IndexEntry {
        val raw = RawEntry.at(index, RootLayout.INDEX_PREFIX + i * NwfFormat.INDEX_ENTRY_BYTES)
        val codec = FrameCodec.fromId(raw.codec) ?: nwfError(
            NwfFormatError.UNKNOWN_CODEC,
            "chunk (${raw.dim}, ${raw.kind}, ${raw.x}, ${raw.z}) has codec ${raw.codec}",
        )
        return IndexEntry(
            x = raw.x,
            z = raw.z,
            dim = raw.dim,
            kind = ChunkKind.fromId(raw.kind) ?: nwfError(NwfFormatError.OUT_OF_BOUNDS, "kind ${raw.kind}"),
            codec = codec,
            flags = raw.flags,
            location = raw.location,
            dictSlot = raw.dictSlot,
            offset = raw.offset.toInt(),
            storedLength = raw.stored.toInt(),
            rawLength = raw.raw.toInt(),
            crc32c = raw.crc,
            dataVersion = raw.dataVersion,
        )
    }

    private fun search(dim: Int, kind: Int, x: Int, z: Int): Int {
        var lo = 0
        var hi = entryCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val base = RootLayout.INDEX_PREFIX + mid * NwfFormat.INDEX_ENTRY_BYTES
            val c = RootLayout.compareKeys(
                index.u8(base + RootLayout.E_DIM),
                index.u8(base + RootLayout.E_KIND),
                index.getInt(base + RootLayout.E_X),
                index.getInt(base + RootLayout.E_Z),
                dim,
                kind,
                x,
                z,
            )
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    override fun toString(): String =
        "NwfV1RootView(generation=$generation, entries=$entryCount, segments=${segments.size}, " +
            "dicts=${dictionaries.size}, inline=$inlineLength B, sha=$rootSha)"
}

// ---- writer ----------------------------------------------------------------------------------------------------

private class RootWriter(private val draft: RootDraft, private val options: RootCodecOptions) {
    private class Section(val type: SectionType, val flags: Int, val stored: ByteArray, val rawLength: Int)

    fun write(): ByteArray {
        if (draft.flags and NwfFormat.ROOT_FLAGS_KNOWN.inv() != 0) {
            nwfError(NwfFormatError.UNKNOWN_FLAGS, "root flags ${draft.flags}")
        }
        if (draft.generation < 1) nwfError(NwfFormatError.OUT_OF_BOUNDS, "generation ${draft.generation}")
        checkBaseFlags(draft.flags, draft.header)
        if (draft.dictionaries.size > NwfFormat.NONE_U16 || draft.segments.size > NwfFormat.NONE_U16) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "${draft.dictionaries.size} dicts, ${draft.segments.size} segs")
        }
        if (draft.entries.size.toLong() >= NwfFormat.MAX_ENTRIES) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "${draft.entries.size} entries")
        }
        val inline = draft.inline.copyRemaining()
        checkEntries(inline)
        val sections = buildSections(inline)
        return assemble(sections)
    }

    private fun checkEntries(inline: ByteArray) {
        val rules = EntryRules(
            draft.header.dimensions.size,
            draft.dictionaries.size,
            draft.segments,
            inline.size.toLong(),
            draft.flags and NwfFormat.ROOT_FLAG_HAS_BASE != 0,
        )
        for (segment in draft.segments) {
            if (segment.length < NwfFormat.SEGMENT_HEADER_BYTES + NwfFormat.SEGMENT_TRAILER_BYTES ||
                segment.length > NwfFormat.MAX_SEGMENT_BYTES
            ) {
                nwfError(NwfFormatError.LIMIT_EXCEEDED, "segment ${segment.sha256} length ${segment.length}")
            }
            val maxFrames = maxFramesIn(segment.length)
            if (segment.frameCount < 0 || segment.frameCount > maxFrames) {
                nwfError(
                    NwfFormatError.OUT_OF_BOUNDS,
                    "segment ${segment.sha256}: frameCount ${segment.frameCount}, at most $maxFrames fit",
                )
            }
        }
        var previous: IndexEntry? = null
        for ((i, entry) in draft.entries.withIndex()) {
            checkFieldRanges(entry, i)
            rules.check(RawEntry.of(entry), "draft entry $i (${entry.key})")
            if (previous != null && previous.key >= entry.key) {
                nwfError(NwfFormatError.UNSORTED_INDEX, "draft entry $i (${entry.key}) after ${previous.key}")
            }
            if (entry.isInline && !entry.isTombstone &&
                crc32c(inline, entry.offset, entry.storedLength) != entry.crc32c
            ) {
                nwfError(NwfFormatError.CRC_MISMATCH, "draft entry $i (${entry.key}): inline payload CRC")
            }
            previous = entry
        }
    }

    private fun checkFieldRanges(entry: IndexEntry, i: Int) {
        val ok = entry.dim in 0..ChunkKey.MAX_DIM && entry.flags in 0..U8_MASK &&
            entry.location in 0..U16_MASK && entry.dictSlot in 0..U16_MASK
        if (!ok) nwfError(NwfFormatError.OUT_OF_BOUNDS, "draft entry $i: a field exceeds its width")
    }

    private fun buildSections(inline: ByteArray): List<Section> = buildList {
        val headerBytes = NwfV1RootCodec.encodeHeader(draft.header)
        add(Section(SectionType.HEADER, 0, headerBytes, headerBytes.size))
        if (draft.dictionaries.isNotEmpty()) {
            val w = LeWriter(RootLayout.LIST_PREFIX + draft.dictionaries.size * NwfFormat.DICT_ENTRY_BYTES)
            w.u16(draft.dictionaries.size)
            for (d in draft.dictionaries) w.i32(d.zstdDictId).bytes(d.sha256.bytes())
            add(raw(SectionType.DICTS, w.toByteArray()))
        }
        if (draft.segments.isNotEmpty()) {
            val w = LeWriter(RootLayout.LIST_PREFIX + draft.segments.size * NwfFormat.SEGMENT_ENTRY_BYTES)
            w.u16(draft.segments.size)
            for (s in draft.segments) w.bytes(s.sha256.bytes()).i32(s.salt).i64(s.length).i32(s.frameCount)
            add(raw(SectionType.SEGMENTS, w.toByteArray()))
        }
        val index = indexBody()
        val packedIndex = ZstdSupport.compress(index, options.sectionLevel)
        add(Section(SectionType.INDEX, NwfFormat.TOC_FLAG_ZSTD, packedIndex, index.size))
        if (inline.isNotEmpty()) add(inlineSection(inline))
        draft.stats?.let { add(Section(SectionType.STATS, NwfFormat.TOC_FLAG_OPTIONAL, it.copyOf(), it.size)) }
    }

    private fun raw(type: SectionType, body: ByteArray) = Section(type, 0, body, body.size)

    private fun inlineSection(inline: ByteArray): Section {
        if (options.packInlineMaxRawBytes > 0 && inline.size <= options.packInlineMaxRawBytes) {
            val packed = ZstdSupport.compress(inline, options.packInlineLevel)
            if (packed.size < inline.size) {
                return Section(SectionType.INLINE, NwfFormat.TOC_FLAG_ZSTD, packed, inline.size)
            }
        }
        return raw(SectionType.INLINE, inline)
    }

    private fun indexBody(): ByteArray {
        val w = LeWriter(RootLayout.INDEX_PREFIX + draft.entries.size * NwfFormat.INDEX_ENTRY_BYTES)
        w.i32(draft.entries.size)
        for (e in draft.entries) {
            w.i32(e.x).i32(e.z).u8(e.dim).u8(e.kind.id).u8(e.codec.id).u8(e.flags)
                .u16(e.location).u16(e.dictSlot)
                .i32(e.offset).i32(e.storedLength).i32(e.rawLength).i32(e.crc32c).i32(e.dataVersion)
        }
        return w.toByteArray()
    }

    private fun assemble(sections: List<Section>): ByteArray {
        val tocEnd = NwfFormat.ROOT_HEADER_BYTES + sections.size * NwfFormat.TOC_ENTRY_BYTES
        val total = tocEnd.toLong() + sections.sumOf { it.stored.size.toLong() }
        if (total > NwfFormat.MAX_ROOT_BYTES) nwfError(NwfFormatError.LIMIT_EXCEEDED, "root of $total bytes")
        val rawTotal = sections.sumOf { it.rawLength.toLong() }
        if (rawTotal > NwfFormat.MAX_ROOT_RAW_BYTES) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "sections decode to $rawTotal bytes in all")
        }
        val toc = LeWriter(sections.size * NwfFormat.TOC_ENTRY_BYTES)
        var offset = tocEnd.toLong()
        for (s in sections) {
            toc.u16(s.type.id).u16(s.flags).u16(NwfFormat.NONE_U16).u16(0)
                .i32(crc32c(s.stored)).i32(s.rawLength).i64(offset).i64(s.stored.size.toLong())
            offset += s.stored.size
        }
        val tocBytes = toc.toByteArray()
        val prefix = LeWriter(RootLayout.OFF_HEADER_CRC)
            .bytes(NwfFormat.ROOT_MAGIC)
            .u16(NwfFormat.MAJOR)
            .u16(NwfFormat.MINOR)
            .i32(draft.flags)
            .i32(sections.size)
            .i64(draft.generation)
            .toByteArray()
        val headerCrc = java.util.zip.CRC32C().apply {
            update(prefix)
            update(tocBytes)
        }.value.toInt()
        val out = LeWriter(total.toInt()).bytes(prefix).i32(headerCrc).i32(0).bytes(tocBytes)
        for (s in sections) out.bytes(s.stored)
        return out.toByteArray()
    }
}

/** The most frames a segment of [length] bytes can hold: header, trailer, and a 28-byte header per frame. */
internal fun maxFramesIn(length: Long): Long =
    (length - NwfFormat.SEGMENT_HEADER_BYTES - NwfFormat.SEGMENT_TRAILER_BYTES) / NwfFormat.FRAME_HEADER_BYTES
