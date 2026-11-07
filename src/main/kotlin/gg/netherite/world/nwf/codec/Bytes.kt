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

/** Copies the remaining bytes of [buffer] (position untouched) into a new array. */
internal fun ByteBuffer.copyRemaining(): ByteArray {
    val view = duplicate()
    val out = ByteArray(view.remaining())
    view.get(out)
    return out
}

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

    fun bytes(value: ByteBuffer): LeWriter = apply {
        val view = value.duplicate()
        ensure(view.remaining())
        buffer.put(view)
    }

    fun toByteArray(): ByteArray = buffer.array().copyOf(buffer.position())
}
