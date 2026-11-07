package gg.netherite.world.nwf.codec

import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress
import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.DictionaryLookup
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.EntryFlags
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
        val scan = LightStripNbtFilter.scan(key.kind, nbt.copyRemaining(), options.lightPolicy, options.heightmapPolicy)
        checkCoordinates(key, scan)
        val filtered = scan.filtered.nbtView().copyRemaining()
        if (filtered.size > NwfFormat.MAX_FRAME_RAW_BYTES) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "$key: ${filtered.size} raw bytes")
        }
        var flags = scan.filtered.entryFlags
        var dictSlot = NwfFormat.NONE_U16
        val base = options.baseNbt
        val dictionary = options.dictionary
        val (codec, payload) = failClosed("encode $key") {
            when {
                options.raw -> {
                    FrameCodec.RAW to filtered
                }
                base != null -> {
                    flags = flags or EntryFlags.BASE_DELTA
                    FrameCodec.ZSTD_BASE_DELTA to
                        ZstdSupport.compressWithRawContent(filtered, options.level, base.copyRemaining())
                }
                dictionary != null -> {
                    dictSlot = options.dictSlot
                    val digested = compressDict(dictionary, options.level)
                    FrameCodec.ZSTD_DICT to ZstdSupport.compress(filtered, options.level, digested)
                }
                else -> {
                    FrameCodec.ZSTD to ZstdSupport.compress(filtered, options.level)
                }
            }
        }
        val header = FrameHeader(
            codec = codec,
            flags = flags,
            rawLength = filtered.size,
            storedLength = payload.size,
            crc32c = crc32c(payload),
            x = key.x,
            z = key.z,
            dim = key.dim,
            kind = key.kind,
            dictSlot = dictSlot,
        )
        return EncodedFrame(header, scan.filtered.dataVersion, ByteBuffer.wrap(payload))
    }

    override fun decode(frame: EncodedFrame, dictionaries: DictionaryLookup, baseNbt: ByteBuffer?): ByteBuffer {
        val h = frame.header
        val what = "frame ${h.key}"
        NwfFrameHeaders.checkConsistent(h, what)
        val payload = frame.payloadView().copyRemaining()
        if (crc32c(payload) != h.crc32c) nwfError(NwfFormatError.CRC_MISMATCH, "$what payload CRC32C")
        val out = failClosed(what) {
            when (h.codec) {
                FrameCodec.RAW -> {
                    payload
                }
                FrameCodec.ZSTD -> {
                    ZstdSupport.decompress(payload, h.rawLength, what)
                }
                FrameCodec.ZSTD_DICT -> {
                    val dict = dictionaries.bySlot(h.dictSlot)
                        ?: nwfError(NwfFormatError.DANGLING_REFERENCE, "$what: dict slot ${h.dictSlot} unresolved")
                    ZstdSupport.decompress(payload, h.rawLength, decompressDict(dict), what)
                }
                FrameCodec.ZSTD_BASE_DELTA -> {
                    val base = baseNbt ?: nwfError(NwfFormatError.DANGLING_REFERENCE, "$what: base frame required")
                    ZstdSupport.decompressWithRawContent(payload, h.rawLength, base.copyRemaining(), what)
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
    private fun checkCoordinates(key: ChunkKey, scan: NbtScan) {
        val checked = key.kind == ChunkKind.CHUNK || key.kind == ChunkKind.ENTITIES
        val mismatch = (scan.x != null && scan.x != key.x) || (scan.z != null && scan.z != key.z)
        if (checked && mismatch) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$key: NBT carries coordinates (${scan.x}, ${scan.z})")
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
}
