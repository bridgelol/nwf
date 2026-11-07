package gg.netherite.world.nwf.codec

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress
import com.github.luben.zstd.ZstdException
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError

/**
 * Single-shot zstd with the NWF frame settings. Every frame carries its content size and the zstd content checksum, so
 * a wrong dictionary or base decodes to an error rather than to plausible bytes. Contexts are created per call and
 * closed at once: no context state (a dictionary in particular) can leak from one frame into the next.
 */
internal object ZstdSupport {
    /** First bytes of a zstd dictionary (magic 0xEC30A437, little-endian). Raw-content dictionaries never start so. */
    private val DICT_MAGIC = byteArrayOf(0x37, 0xA4.toByte(), 0x30, 0xEC.toByte())

    /** Upper bound of output bytes per stored byte: a 128 KiB block from a 3-byte header plus one RLE byte. */
    private const val MAX_EXPANSION: Long = 128L * 1024 / 4

    val levels: IntRange = Zstd.minCompressionLevel()..Zstd.maxCompressionLevel()

    fun compress(src: ByteArray, level: Int): ByteArray =
        ZstdCompressCtx().use { ctx ->
            configure(ctx, level)
            ctx.compress(src)
        }

    fun compress(src: ByteArray, level: Int, dict: ZstdDictCompress): ByteArray =
        ZstdCompressCtx().use { ctx ->
            configure(ctx, level)
            ctx.loadDict(dict)
            ctx.compress(src)
        }

    /** Compresses with [rawContent] (the base frame's NBT) loaded as a raw-content dictionary (ZSTD_BASE_DELTA). */
    fun compressWithRawContent(src: ByteArray, level: Int, rawContent: ByteArray): ByteArray {
        requireRawContent(rawContent)
        return ZstdCompressCtx().use { ctx ->
            configure(ctx, level)
            ctx.loadDict(rawContent)
            ctx.compress(src)
        }
    }

    fun decompress(src: ByteArray, rawLength: Int, what: String): ByteArray =
        ZstdDecompressCtx().use { ctx -> decompressExact(ctx, src, rawLength, what) }

    fun decompress(src: ByteArray, rawLength: Int, dict: ZstdDictDecompress, what: String): ByteArray =
        ZstdDecompressCtx().use { ctx ->
            ctx.loadDict(dict)
            decompressExact(ctx, src, rawLength, what)
        }

    fun decompressWithRawContent(src: ByteArray, rawLength: Int, rawContent: ByteArray, what: String): ByteArray {
        requireRawContent(rawContent)
        return ZstdDecompressCtx().use { ctx ->
            ctx.loadDict(rawContent)
            decompressExact(ctx, src, rawLength, what)
        }
    }

    private fun configure(ctx: ZstdCompressCtx, level: Int) {
        ctx.setLevel(level)
        ctx.setChecksum(true)
        ctx.setContentSize(true)
        ctx.setDictID(true)
    }

    /** Decompresses exactly [rawLength] bytes; any other size, a bad checksum or a zstd error fails closed. */
    private fun decompressExact(ctx: ZstdDecompressCtx, src: ByteArray, rawLength: Int, what: String): ByteArray {
        if (rawLength < 0 || rawLength > NwfFormat.MAX_FRAME_RAW_BYTES) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "$what: rawLength $rawLength")
        }
        // The declared size is checked before allocating, so a damaged length never drives a large allocation.
        val declared = try {
            Zstd.getFrameContentSize(src)
        } catch (e: ZstdException) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: zstd frame header: ${e.message}", e)
        }
        if (declared != rawLength.toLong()) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: zstd frame declares $declared bytes, expected $rawLength")
        }
        // The stored body is exactly one zstd frame: no trailing bytes, no second frame (SPEC 2, 5).
        val frameLength = try {
            Zstd.findFrameCompressedSize(src)
        } catch (e: ZstdException) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: zstd frame: ${e.message}", e)
        }
        if (Zstd.isError(frameLength) || frameLength != src.size.toLong()) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: ${src.size} stored bytes are not exactly one zstd frame")
        }
        // A zstd block yields at most 128 KiB from at least 4 stored bytes, so a size beyond that ratio is a damaged
        // header; refusing it keeps a tiny body from driving a large allocation.
        if (rawLength.toLong() > src.size.toLong() * MAX_EXPANSION) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: $rawLength bytes cannot come from ${src.size} stored bytes")
        }
        val out = ByteArray(rawLength)
        val written = try {
            ctx.decompressByteArray(out, 0, rawLength, src, 0, src.size)
        } catch (e: ZstdException) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: zstd: ${e.message}", e)
        }
        if (written != rawLength) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: zstd produced $written bytes, expected $rawLength")
        }
        return out
    }

    private fun requireRawContent(rawContent: ByteArray) {
        if (rawContent.size >= DICT_MAGIC.size && DICT_MAGIC.indices.all { rawContent[it] == DICT_MAGIC[it] }) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "base content starts with the zstd dictionary magic")
        }
    }
}
