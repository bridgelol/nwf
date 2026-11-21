package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.NwfFormatException
import gg.netherite.world.nwf.Sha256
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.CRC32C

internal const val U8_MASK: Int = 0xFF
internal const val U16_MASK: Int = 0xFFFF
internal const val U32_MASK: Long = 0xFFFF_FFFFL

/** Throws the one exception type NWF readers use for malformed input. */
internal fun nwfError(reason: NwfFormatError, message: String, cause: Throwable? = null): Nothing =
    throw NwfFormatException(reason, message, cause)

/**
 * Runs [block] and turns any runtime failure into an [NwfFormatException], so fuzzed input never escapes as another
 * exception type. [NwfFormatException] is an `IOException` and passes through untouched.
 */
internal inline fun <T> failClosed(what: String, block: () -> T): T =
    try {
        block()
    } catch (e: RuntimeException) {
        val reason = when (e) {
            is BufferUnderflowException -> NwfFormatError.TRUNCATED
            is IndexOutOfBoundsException -> NwfFormatError.OUT_OF_BOUNDS
            else -> NwfFormatError.BAD_PAYLOAD
        }
        throw NwfFormatException(reason, "$what: ${e.javaClass.simpleName}: ${e.message}", e)
    }

/** Borrowed slice of a [ByteArray]: `[offset, offset + length)`. */
internal class ByteSlice(val array: ByteArray, val offset: Int, val length: Int) {
    fun copy(): ByteArray = array.copyOfRange(offset, offset + length)

    fun toExactByteArray(): ByteArray = if (offset == 0 && length == array.size) array else copy()
}

/** Copies the remaining bytes of [buffer] (position untouched) into a new array. */
internal fun ByteBuffer.copyRemaining(): ByteArray {
    val view = duplicate()
    val out = ByteArray(view.remaining())
    view.get(out)
    return out
}

/** Array-backed slice of [ByteBuffer]'s remaining bytes without copying, or null when read-only or direct. */
internal fun ByteBuffer.arraySliceOrNull(): ByteSlice? =
    if (hasArray()) ByteSlice(array(), arrayOffset() + position(), remaining()) else null

/** Returns [ByteBuffer]'s backing array directly when it matches remaining bytes, else a copy. */
internal fun ByteBuffer.toExactByteArrayOrCopy(): ByteArray =
    if (hasArray() && arrayOffset() + position() == 0 && remaining() == array().size) array() else copyRemaining()

internal fun ByteArray.littleEndian(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

internal fun ByteBuffer.u8(index: Int): Int = get(index).toInt() and U8_MASK

internal fun ByteBuffer.u16(index: Int): Int = getShort(index).toInt() and U16_MASK

internal fun ByteBuffer.u32(index: Int): Long = getInt(index).toLong() and U32_MASK

/** CRC32C of the remaining bytes of [buffer]; the position is untouched. */
internal fun crc32c(buffer: ByteBuffer): Int {
    val crc = CRC32C()
    crc.update(buffer.duplicate())
    return crc.value.toInt()
}

internal fun crc32c(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
    val crc = CRC32C()
    crc.update(bytes, offset, length)
    return crc.value.toInt()
}

internal fun sha256(bytes: ByteArray): Sha256 = Sha256(MessageDigest.getInstance("SHA-256").digest(bytes))

internal fun sha256(buffer: ByteBuffer): Sha256 {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(buffer.duplicate())
    return Sha256(digest.digest())
}

/** A growable little-endian byte sink for writers. */
internal class LeWriter(initialCapacity: Int = 256) {
    private var buffer: ByteBuffer = ByteBuffer.allocate(initialCapacity).order(ByteOrder.LITTLE_ENDIAN)

    val size: Int get() = buffer.position()

    private fun ensure(extra: Int) {
        if (buffer.remaining() >= extra) return
        var capacity = buffer.capacity().coerceAtLeast(1)
        val needed = buffer.position().toLong() + extra
        while (capacity < needed) capacity = Math.multiplyExact(capacity, 2)
        val grown = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN)
        buffer.flip()
        grown.put(buffer)
        buffer = grown
    }

    fun u8(value: Int): LeWriter = apply {
        ensure(1)
        buffer.put(value.toByte())
    }

    fun u16(value: Int): LeWriter = apply {
        ensure(2)
        buffer.putShort(value.toShort())
    }

    fun i32(value: Int): LeWriter = apply {
        ensure(4)
        buffer.putInt(value)
    }

    fun i64(value: Long): LeWriter = apply {
        ensure(8)
        buffer.putLong(value)
    }

    fun bytes(value: ByteArray, offset: Int = 0, length: Int = value.size - offset): LeWriter = apply {
        ensure(length)
        buffer.put(value, offset, length)
    }

    fun bytes(slice: ByteSlice): LeWriter = bytes(slice.array, slice.offset, slice.length)

    fun bytes(value: ByteBuffer): LeWriter = apply {
        val view = value.duplicate()
        ensure(view.remaining())
        buffer.put(view)
    }

    fun zeros(count: Int): LeWriter = apply {
        ensure(count)
        repeat(count) { buffer.put(0) }
    }

    fun crc32c(offset: Int, length: Int): Int = crc32c(buffer.array(), offset, length)

    fun patchInt(offset: Int, value: Int) {
        buffer.putInt(offset, value)
    }

    fun toByteArray(): ByteArray = buffer.array().copyOf(buffer.position())

    /** Returns the backing array directly when the written bytes fill it; caller must not use the writer afterwards. */
    fun takeByteArray(): ByteArray =
        if (buffer.position() == buffer.capacity()) buffer.array() else buffer.array().copyOf(buffer.position())
}
