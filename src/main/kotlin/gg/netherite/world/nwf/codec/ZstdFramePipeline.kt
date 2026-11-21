package gg.netherite.world.nwf.codec

import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress
import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.DictionaryLookup
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.FilteredNbt
import gg.netherite.world.nwf.FrameCodec
import gg.netherite.world.nwf.FrameEncodeOptions
import gg.netherite.world.nwf.FrameHeader
import gg.netherite.world.nwf.FramePipeline
import gg.netherite.world.nwf.NwfDictionary
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.Sha256
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * The frame pipeline: NBT -> [LightStripNbtFilter] -> zstd (plain, trained dictionary, or base delta) -> frame
 * with the CRC32C of its payload; and the inverse. CPU only, no IO, thread-safe. One instance per process: it caches
 * the digested form of each dictionary (by SHA-256, and by level for compression), so dictionaries are digested once.
 *
 * Codec choice in [encode]: `raw` -> RAW; a `baseNbt` -> ZSTD_BASE_DELTA (BASE_DELTA flag, no dictionary slot); a
 * `dictionary` -> ZSTD_DICT; otherwise ZSTD. The encoder refuses CHUNK / ENTITIES NBT whose own coordinates differ from
 * the key. [decode] checks the payload CRC32C before decompressing, then the exact raw length and the zstd content
 * checksum, so a wrong dictionary or base never yields plausible bytes. Every failure is a
 * [gg.netherite.world.nwf.NwfFormatException].
 */
class ZstdFramePipeline : FramePipeline, AutoCloseable {
    private data class CompressKey(val sha: Sha256, val level: Int)

    private val compressDicts = ConcurrentHashMap<CompressKey, ZstdDictCompress>()
    private val decompressDicts = ConcurrentHashMap<Sha256, ZstdDictDecompress>()

    override fun encode(key: ChunkKey, nbt: ByteBuffer, options: FrameEncodeOptions): EncodedFrame {
        checkOptions(options)
        val scan = LightStripNbtFilter.scan(
            key.kind,
            nbt,
            options.lightPolicy,
            options.heightmapPolicy,
            copyIfUnmodified = false,
        )
        checkCoordinates(key, scan.x, scan.z)
        val filtered = scan.filtered
        val slice = filtered.backing ?: ByteSlice(filtered.nbtView().copyRemaining(), 0, filtered.nbtView().remaining())
        return encodeSlice(key, slice, filtered.dataVersion, filtered.entryFlags, options, scan.ownsBytes)
    }

    /**
     * Encodes an already-filtered [FilteredNbt] (such as from [LightStripNbtFilter.filter]) without re-scanning the
     * NBT stream. Validates [options], [FilteredNbt.entryFlags], and coordinates carried on [filtered].
     */
    override fun encodeFiltered(key: ChunkKey, filtered: FilteredNbt, options: FrameEncodeOptions): EncodedFrame {
        checkOptions(options)
        if (filtered.entryFlags and FILTER_FLAGS_MASK.inv() != 0) {
            nwfError(NwfFormatError.UNKNOWN_FLAGS, "$key: filtered flags ${filtered.entryFlags}")
        }
        checkCoordinates(key, filtered.x, filtered.z)
        val slice = filtered.backing ?: filtered.nbtView().copyRemaining().let { ByteSlice(it, 0, it.size) }
        return encodeSlice(key, slice, filtered.dataVersion, filtered.entryFlags, options, ownsBytes = false)
    }

    @Suppress("LongParameterList")
    private fun encodeSlice(
        key: ChunkKey,
        slice: ByteSlice,
        dataVersion: Int,
        entryFlags: Int,
        options: FrameEncodeOptions,
        ownsBytes: Boolean,
    ): EncodedFrame {
        if (slice.length > NwfFormat.MAX_FRAME_RAW_BYTES) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "$key: ${slice.length} raw bytes")
        }
        var flags = entryFlags
        var dictSlot = NwfFormat.NONE_U16
        val base = options.baseNbt
        val dictionary = options.dictionary
        val (codec, payload) = failClosed("encode $key") {
            when {
                options.raw -> {
                    val rawBytes = if (ownsBytes && slice.offset == 0 && slice.length == slice.array.size) {
                        slice.array
                    } else {
                        slice.copy()
                    }
                    FrameCodec.RAW to rawBytes
                }
                base != null -> {
                    flags = flags or EntryFlags.BASE_DELTA
                    val baseBytes = base.toExactByteArrayOrCopy()
                    FrameCodec.ZSTD_BASE_DELTA to
                        ZstdSupport.compressWithRawContent(
                            slice.array,
                            options.level,
                            baseBytes,
                            slice.offset,
                            slice.length,
                        )
                }
                dictionary != null -> {
                    dictSlot = options.dictSlot
                    val digested = compressDict(dictionary, options.level)
                    FrameCodec.ZSTD_DICT to
                        ZstdSupport.compress(slice.array, options.level, digested, slice.offset, slice.length)
                }
                else -> {
                    FrameCodec.ZSTD to
                        ZstdSupport.compress(slice.array, options.level, slice.offset, slice.length)
                }
            }
        }
        val header = FrameHeader(
            codec = codec,
            flags = flags,
            rawLength = slice.length,
            storedLength = payload.size,
            crc32c = crc32c(payload),
            x = key.x,
            z = key.z,
            dim = key.dim,
            kind = key.kind,
            dictSlot = dictSlot,
        )
        return EncodedFrame(header, dataVersion, ByteBuffer.wrap(payload))
    }

    override fun decode(frame: EncodedFrame, dictionaries: DictionaryLookup, baseNbt: ByteBuffer?): ByteBuffer {
        val h = frame.header
        val what = "frame ${h.key}"
        NwfFrameHeaders.checkConsistent(h, what)
        val payload = frame.backing ?: frame.payloadView().copyRemaining().let { ByteSlice(it, 0, it.size) }
        if (crc32c(payload.array, payload.offset, payload.length) != h.crc32c) {
            nwfError(NwfFormatError.CRC_MISMATCH, "$what payload CRC32C")
        }
        val out = failClosed(what) {
            when (h.codec) {
                FrameCodec.RAW -> {
                    payload.copy()
                }
                FrameCodec.ZSTD -> {
                    ZstdSupport.decompress(payload.array, h.rawLength, what, payload.offset, payload.length)
                }
                FrameCodec.ZSTD_DICT -> {
                    val dict = dictionaries.bySlot(h.dictSlot)
                        ?: nwfError(NwfFormatError.DANGLING_REFERENCE, "$what: dict slot ${h.dictSlot} unresolved")
                    ZstdSupport.decompress(
                        payload.array,
                        h.rawLength,
                        decompressDict(dict),
                        what,
                        payload.offset,
                        payload.length,
                    )
                }
                FrameCodec.ZSTD_BASE_DELTA -> {
                    val base = baseNbt ?: nwfError(NwfFormatError.DANGLING_REFERENCE, "$what: base frame required")
                    val baseBytes = base.toExactByteArrayOrCopy()
                    ZstdSupport.decompressWithRawContent(
                        payload.array,
                        h.rawLength,
                        baseBytes,
                        what,
                        payload.offset,
                        payload.length,
                    )
                }
            }
        }
        return ByteBuffer.wrap(out)
    }

    /** Releases the cached native dictionaries. The pipeline must not be used afterwards. */
    override fun close() {
        compressDicts.values.forEach { it.close() }
        decompressDicts.values.forEach { it.close() }
        compressDicts.clear()
        decompressDicts.clear()
    }

    private fun checkOptions(options: FrameEncodeOptions) {
        require(options.level in ZstdSupport.levels) { "zstd level ${options.level} outside ${ZstdSupport.levels}" }
        require((options.dictionary == null) == (options.dictSlot == NwfFormat.NONE_U16)) {
            "dictionary and dictSlot go together (slot ${options.dictSlot})"
        }
        require(options.dictSlot in 0..U16_MASK) { "dictSlot ${options.dictSlot}" }
        require(options.baseNbt == null || options.dictionary == null) { "base delta excludes a dictionary" }
        require(!options.raw || (options.baseNbt == null && options.dictionary == null)) {
            "a RAW frame takes neither a dictionary nor a base"
        }
    }

    /** CHUNK and ENTITIES coordinates must match the key. POI record positions are accepted wherever they lie. */
    private fun checkCoordinates(key: ChunkKey, nbtX: Int?, nbtZ: Int?) {
        val checked = key.kind == ChunkKind.CHUNK || key.kind == ChunkKind.ENTITIES
        val mismatch = (nbtX != null && nbtX != key.x) || (nbtZ != null && nbtZ != key.z)
        if (checked && mismatch) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$key: NBT carries coordinates ($nbtX, $nbtZ)")
        }
    }

    private fun compressDict(dict: NwfDictionary, level: Int): ZstdDictCompress =
        compressDicts.computeIfAbsent(CompressKey(dict.sha256, level)) {
            ZstdDictCompress(verifiedBytes(dict), level)
        }

    private fun decompressDict(dict: NwfDictionary): ZstdDictDecompress =
        decompressDicts.computeIfAbsent(dict.sha256) { ZstdDictDecompress(verifiedBytes(dict)) }

    private fun verifiedBytes(dict: NwfDictionary): ByteArray {
        if (!dict.verified()) nwfError(NwfFormatError.SHA_MISMATCH, "dictionary ${dict.sha256}: bytes differ")
        return dict.bytes()
    }

    private companion object {
        const val FILTER_FLAGS_MASK = EntryFlags.LIGHT_STRIPPED or EntryFlags.HEIGHTMAPS_STRIPPED
    }
}
