package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.TransitFrameCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The in-process transit envelope between a storage hook's `encode` -> `stage` and `read` -> `decode` calls. Never
 * persisted. Little-endian:
 *
 * ```
 * 0 u16 0x544E "NT"   2 u16 version = 1   4 u32 dataVersion   8 the 28-byte frame header   36 payload
 * ```
 *
 * [fromTransit] checks the envelope, the frame header rules and the payload CRC32C, so a damaged frame is refused at
 * `stage` before it can reach a commit, and copies the payload out of the caller's buffer.
 */
object NwfV1TransitCodec : TransitFrameCodec {
    const val MARKER: Int = 0x544E
    const val VERSION: Int = 1
    const val PREFIX_BYTES: Int = 8
    const val ENVELOPE_BYTES: Int = PREFIX_BYTES + NwfFormat.FRAME_HEADER_BYTES

    override fun toTransit(frame: EncodedFrame): ByteBuffer {
        val out = LeWriter(ENVELOPE_BYTES + frame.header.storedLength)
        out.u16(MARKER).u16(VERSION).i32(frame.dataVersion)
        NwfFrameHeaders.write(out, frame.header)
        out.bytes(frame.payloadView())
        return ByteBuffer.wrap(out.toByteArray())
    }

    override fun fromTransit(buffer: ByteBuffer): EncodedFrame = failClosed("transit frame") {
        val b = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < ENVELOPE_BYTES) nwfError(NwfFormatError.TRUNCATED, "${b.remaining()} bytes")
        if (b.u16(0) != MARKER) nwfError(NwfFormatError.BAD_MAGIC, "not a transit frame")
        val version = b.u16(2)
        if (version != VERSION) nwfError(NwfFormatError.UNSUPPORTED_MAJOR, "transit version $version")
        val header = NwfFrameHeaders.read(b, PREFIX_BYTES)
        val payloadLength = b.remaining() - ENVELOPE_BYTES
        if (payloadLength != header.storedLength) {
            val short = payloadLength < header.storedLength
            val reason = if (short) NwfFormatError.TRUNCATED else NwfFormatError.OUT_OF_BOUNDS
            nwfError(reason, "payload of $payloadLength bytes, header says ${header.storedLength}")
        }
        val payload = ByteArray(payloadLength)
        b.get(ENVELOPE_BYTES, payload)
        if (crc32c(payload) != header.crc32c) nwfError(NwfFormatError.CRC_MISMATCH, "transit frame ${header.key}")
        EncodedFrame(header, b.getInt(4), ByteBuffer.wrap(payload))
    }
}
