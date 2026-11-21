package gg.netherite.world.nwf.codec

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDecompressCtx
import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress
import com.github.luben.zstd.ZstdException
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import java.lang.ref.Cleaner
import java.util.concurrent.atomic.LongAdder

/**
 * Process-wide count of zstd decompressions by the NWF codecs (frames and root sections alike). Diagnostics: tests use
 * it to prove a packed root's INLINE is decompressed once per parse, never once per chunk read. A [LongAdder], so the
 * region threads that decode chunks concurrently each add to their own cell and never contend on one word.
 */
object NwfZstdCounters {
    internal val decompressions = LongAdder()

    /** zstd decompressions since the process started. */
    fun decompressions(): Long = decompressions.sum()
}

/**
 * Single-shot zstd with the NWF frame settings. Every frame carries its content size and the zstd content checksum, so
 * a wrong dictionary or base decodes to an error rather than to plausible bytes. Decompression contexts and compression
 * contexts up to the default level are reused per thread and explicitly reset both before use and in `finally`
 * (including on error) so parameters, dictionaries and raw prefixes never leak across calls; higher compression levels
 * use a fresh context per call so their workspace is freed at once.
 */
internal object ZstdSupport {
    /** First bytes of a zstd dictionary (magic 0xEC30A437, little-endian). Raw-content dictionaries never start so. */
    private val DICT_MAGIC = byteArrayOf(0x37, 0xA4.toByte(), 0x30, 0xEC.toByte())

    /** Upper bound of output bytes per stored byte: a 128 KiB block from a 3-byte header plus one RLE byte. */
    private const val MAX_EXPANSION: Long = 128L * 1024 / 4

    private val CLEANER: Cleaner = Cleaner.create()

    /** The highest level a per-thread compression context is reused for: zstd's and NWF's default save level. */
    private const val PER_THREAD_MAX_LEVEL = 3

    private class CompressHolder {
        val ctx = ZstdCompressCtx()
        private val cleanable = registerClose(this, ctx)

        fun close() = cleanable.clean()
    }

    private class DecompressHolder {
        val ctx = ZstdDecompressCtx()
        private val cleanable = registerClose(this, ctx)

        fun close() = cleanable.clean()
    }

    private val compressCtx: ThreadLocal<CompressHolder> = ThreadLocal.withInitial(::CompressHolder)
    private val decompressCtx: ThreadLocal<DecompressHolder> = ThreadLocal.withInitial(::DecompressHolder)

    val levels: IntRange = Zstd.minCompressionLevel()..Zstd.maxCompressionLevel()

    fun compress(src: ByteArray, level: Int, offset: Int = 0, length: Int = src.size - offset): ByteArray =
        withCompressCtx(level) { ctx ->
            configure(ctx, level)
            compressSlice(ctx, src, offset, length)
        }

    fun compress(
        src: ByteArray,
        level: Int,
        dict: ZstdDictCompress,
        offset: Int = 0,
        length: Int = src.size - offset,
    ): ByteArray = withCompressCtx(level) { ctx ->
        configure(ctx, level)
        ctx.loadDict(dict)
        compressSlice(ctx, src, offset, length)
    }

    /** Compresses with [rawContent] (the base frame's NBT) loaded as a raw-content dictionary (ZSTD_BASE_DELTA). */
    fun compressWithRawContent(
        src: ByteArray,
        level: Int,
        rawContent: ByteArray,
        offset: Int = 0,
        length: Int = src.size - offset,
    ): ByteArray {
        requireRawContent(rawContent)
        return withCompressCtx(level) { ctx ->
            configure(ctx, level)
            ctx.loadDict(rawContent)
            compressSlice(ctx, src, offset, length)
        }
    }

    fun decompress(
        src: ByteArray,
        rawLength: Int,
        what: String,
        offset: Int = 0,
        length: Int = src.size - offset,
    ): ByteArray = withDecompressCtx { ctx ->
        decompressExact(ctx, src, offset, length, rawLength, what)
    }

    fun decompress(
        src: ByteArray,
        rawLength: Int,
        dict: ZstdDictDecompress,
        what: String,
        offset: Int = 0,
        length: Int = src.size - offset,
    ): ByteArray = withDecompressCtx { ctx ->
        ctx.loadDict(dict)
        decompressExact(ctx, src, offset, length, rawLength, what)
    }

    fun decompressWithRawContent(
        src: ByteArray,
        rawLength: Int,
        rawContent: ByteArray,
        what: String,
        offset: Int = 0,
        length: Int = src.size - offset,
    ): ByteArray {
        requireRawContent(rawContent)
        return withDecompressCtx { ctx ->
            ctx.loadDict(rawContent)
            decompressExact(ctx, src, offset, length, rawLength, what)
        }
    }

    private fun registerClose(owner: Any, resource: AutoCloseable): Cleaner.Cleanable =
        CLEANER.register(owner) { runCatching { resource.close() } }

    /**
     * Runs [block] on a compression context. Up to [PER_THREAD_MAX_LEVEL] (the default save level, the hot path) the
     * thread's reused context; above it (compaction, packed INLINE, tooling) a fresh context closed right after, since
     * zstd keeps a context's workspace for the largest level and input it ever saw and a per-thread high-level
     * context would hold that native memory for the thread's life.
     */
    private inline fun <T> withCompressCtx(level: Int, block: (ZstdCompressCtx) -> T): T {
        if (level > PER_THREAD_MAX_LEVEL) {
            return ZstdCompressCtx().use { ctx -> block(ctx) }
        }
        val holder = compressCtx.get()
        val ctx = holder.ctx
        var resetAfter = false
        try {
            ctx.reset()
            val result = block(ctx)
            ctx.reset()
            resetAfter = true
            return result
        } finally {
            if (!resetAfter) {
                try {
                    ctx.reset()
                } catch (_: Throwable) {
                    compressCtx.remove()
                    runCatching { holder.close() }
                }
            }
        }
    }

    private inline fun <T> withDecompressCtx(block: (ZstdDecompressCtx) -> T): T {
        val holder = decompressCtx.get()
        val ctx = holder.ctx
        var resetAfter = false
        try {
            ctx.reset()
            val result = block(ctx)
            ctx.reset()
            resetAfter = true
            return result
        } finally {
            if (!resetAfter) {
                try {
                    ctx.reset()
                } catch (_: Throwable) {
                    decompressCtx.remove()
                    runCatching { holder.close() }
                }
            }
        }
    }

    private fun configure(ctx: ZstdCompressCtx, level: Int) {
        ctx.setLevel(level)
        ctx.setChecksum(true)
        ctx.setContentSize(true)
        ctx.setDictID(true)
    }

    private fun compressSlice(ctx: ZstdCompressCtx, src: ByteArray, offset: Int, length: Int): ByteArray {
        val bound = Zstd.compressBound(length.toLong())
        if (bound > Int.MAX_VALUE) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "compressed bound $bound exceeds Int.MAX_VALUE")
        }
        val out = ByteArray(bound.toInt())
        val written = ctx.compressByteArray(out, 0, out.size, src, offset, length)
        return if (written == out.size) out else out.copyOf(written)
    }

    /** Decompresses exactly [rawLength] bytes; any other size, a bad checksum or a zstd error fails closed. */
    private fun decompressExact(
        ctx: ZstdDecompressCtx,
        src: ByteArray,
        offset: Int,
        length: Int,
        rawLength: Int,
        what: String,
    ): ByteArray {
        if (rawLength < 0 || rawLength > NwfFormat.MAX_FRAME_RAW_BYTES) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "$what: rawLength $rawLength")
        }
        if (length <= 0) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: empty zstd frame")
        }
        // The declared size is checked before allocating, so a damaged length never drives a large allocation.
        val declared = try {
            Zstd.getFrameContentSize(src, offset, length)
        } catch (e: ZstdException) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: zstd frame header: ${e.message}", e)
        }
        if (declared != rawLength.toLong()) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: zstd frame declares $declared bytes, expected $rawLength")
        }
        // The stored body is exactly one zstd frame: no trailing bytes, no second frame (SPEC 2, 5).
        val frameLength = try {
            Zstd.findFrameCompressedSize(src, offset, length)
        } catch (e: ZstdException) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: zstd frame: ${e.message}", e)
        }
        if (Zstd.isError(frameLength) || frameLength != length.toLong()) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: $length stored bytes are not exactly one zstd frame")
        }
        // A zstd block yields at most 128 KiB from at least 4 stored bytes, so a size beyond that ratio is a damaged
        // header; refusing it keeps a tiny body from driving a large allocation.
        if (rawLength.toLong() > length.toLong() * MAX_EXPANSION) {
            nwfError(NwfFormatError.BAD_PAYLOAD, "$what: $rawLength bytes cannot come from $length stored bytes")
        }
        val out = ByteArray(rawLength)
        NwfZstdCounters.decompressions.increment()
        val written = try {
            ctx.decompressByteArray(out, 0, rawLength, src, offset, length)
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
