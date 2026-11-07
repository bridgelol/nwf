@file:Suppress("MaxLineLength") // dense assertion tables read better unwrapped

package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.Sha256
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NwfV1SegmentAndTransitTest {
    private val pipeline = ZstdFramePipeline()

    private fun frames(): List<EncodedFrame> =
        sampleNbt().map { (key, nbt) -> pipeline.encode(key, ByteBuffer.wrap(nbt), options()) }

    private fun parse(bytes: ByteArray, sha: Sha256? = null) = NwfV1SegmentCodec.parse(ByteBuffer.wrap(bytes), sha)

    @Test
    fun `segment round trips sorted frames and reports their offsets`() {
        val frames = frames()
        val written = NwfV1SegmentCodec.write(frames.shuffled(kotlin.random.Random(3)))
        val bytes = written.bytes()
        assertEquals(sha256(bytes), written.sha256)
        assertEquals(bytes.size.toLong(), written.length)
        val view = parse(bytes, written.sha256)
        assertEquals(frames.size, view.frameCount)
        assertEquals(NwfFormat.SEGMENT_FLAG_SORTED, view.flags)
        val byKey = frames.associateBy { it.key }
        val refs = view.frames().toList()
        assertEquals(byKey.keys.sorted(), refs.map { it.header.key })
        for (ref in refs) {
            val frame = byKey.getValue(ref.header.key)
            assertEquals(frame.header, ref.header)
            assertEquals(written.frameOffsets.getValue(ref.header.key), ref.headerOffset)
            assertArrayEquals(frame.payloadView().toBytes(), view.payload(ref).toBytes())
            val entry = entryOf(frame, 0, ref.headerOffset)
            assertArrayEquals(frame.payloadView().toBytes(), view.payload(entry).toBytes())
            assertEquals(frame.header, view.header(entry))
            // Range reads parse the header straight from the object bytes.
            assertEquals(frame.header, NwfFrameHeaders.read(ByteBuffer.wrap(bytes), ref.headerOffset))
        }
        assertArrayEquals(bytes, NwfV1SegmentCodec.write(frames).bytes())
        assertArrayEquals(NwfFormat.SEGMENT_MAGIC, bytes.copyOf(4))
    }

    @Test
    fun `a key given twice is refused`() {
        val f = frames().first()
        assertThrows<IllegalArgumentException> { NwfV1SegmentCodec.write(listOf(f, f)) }
    }

    @Test
    fun `whole-object reads detect every bit flip and every truncation`() {
        val written = NwfV1SegmentCodec.write(frames().take(3))
        val bytes = written.bytes()
        for (bit in 0 until bytes.size * 8 step 3) {
            assertTrue(!failsClosedOrParses { parse(bytes.flipBit(bit), written.sha256) }, "bit $bit")
        }
        for (length in 0 until bytes.size) {
            assertTrue(!failsClosedOrParses { parse(bytes.copyOf(length), written.sha256) }, "length $length")
            assertTrue(!failsClosedOrParses { parse(bytes.copyOf(length)) }, "length $length without sha")
        }
        assertFormatError(NwfFormatError.SHA_MISMATCH) { parse(bytes, Sha256(ByteArray(32))) }
    }

    @Test
    fun `range reads without a sha still check header, trailer and frame structure, and payload damage hits the CRC`() {
        val written = NwfV1SegmentCodec.write(frames().take(3))
        val bytes = written.bytes()
        for (bit in 0 until NwfFormat.SEGMENT_HEADER_BYTES * 8) {
            assertTrue(!failsClosedOrParses { parse(bytes.flipBit(bit)) }, "header bit $bit")
        }
        for (bit in (bytes.size - 4) * 8 until bytes.size * 8) {
            assertFormatError(NwfFormatError.CRC_MISMATCH) { parse(bytes.flipBit(bit)) }
        }
        val first = written.frameOffsets.values.min()
        val payloadByte = first + NwfFormat.FRAME_HEADER_BYTES + 2
        val view = parse(bytes.flipBit(payloadByte * 8))
        val ref = view.frames().first { it.headerOffset == first }
        val frame = EncodedFrame(ref.header, 0, view.payload(ref))
        assertFormatError(NwfFormatError.CRC_MISMATCH) { pipeline.decode(frame, NO_DICTS, null) }
    }

    @Test
    fun `bad magic, unknown major, bad counts and stray offsets fail closed`() {
        val bytes = NwfV1SegmentCodec.write(frames().take(2)).bytes()
        assertFormatError(NwfFormatError.BAD_MAGIC) {
            parse(bytes.copyOf().also { System.arraycopy(NwfFormat.ROOT_MAGIC, 0, it, 0, 4) })
        }
        val major = bytes.copyOf()
        ByteBuffer.wrap(major).order(ByteOrder.LITTLE_ENDIAN).putShort(4, 2)
        assertFormatError(NwfFormatError.UNSUPPORTED_MAJOR) { parse(major) }

        fun withCount(n: Int): ByteArray {
            val b = bytes.copyOf()
            val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(8, n)
            buf.putInt(b.size - 4, crc(b.copyOf(32)))
            return b
        }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { parse(withCount(1)) }
        assertFormatError(NwfFormatError.TRUNCATED) { parse(withCount(3)) }
        assertFormatError(NwfFormatError.TRUNCATED) { parse(withCount(-1)) }
        val anyFrame = frames().first()
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { parse(bytes).payload(entryOf(anyFrame, 0, 33)) }
    }

    @Test
    fun `an unknown codec in one frame header fails closed for that frame only`() {
        val written = NwfV1SegmentCodec.write(frames().take(2))
        val offsets = written.frameOffsets.values.sorted()
        val bytes = written.bytes()
        bytes[offsets[1] + 2] = 9
        val view = parse(bytes)
        assertEquals(2, view.frameCount)
        view.headerAt(offsets[0])
        assertFormatError(NwfFormatError.UNKNOWN_CODEC) { view.headerAt(offsets[1]) }
        assertFormatError(NwfFormatError.UNKNOWN_CODEC) { view.frames().toList() }
    }

    @Test
    fun `reads through an index entry check the frame header on every read`() {
        val frames = frames().take(2)
        val written = NwfV1SegmentCodec.write(frames)
        val view = parse(written.bytes())
        val frame = frames.first()
        val good = entryOf(frame, 0, written.frameOffsets.getValue(frame.key))
        assertArrayEquals(frame.payloadView().toBytes(), view.payload(good).toBytes())
        for (stale in listOf(good.copy(x = good.x + 1), good.copy(dim = 1), good.copy(storedLength = good.storedLength + 1), good.copy(crc32c = good.crc32c xor 1))) {
            assertFormatError(NwfFormatError.DANGLING_REFERENCE) { view.payload(stale) }
            assertFormatError(NwfFormatError.DANGLING_REFERENCE) { view.header(stale) }
            assertFormatError(NwfFormatError.DANGLING_REFERENCE) {
                NwfFrameHeaders.readFor(ByteBuffer.wrap(written.bytes()), good.offset, stale)
            }
        }
        // Another frame's offset under this frame's entry.
        val other = written.frameOffsets.getValue(frames[1].key)
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) { view.payload(good.copy(offset = other)) }
        // A forged frame ref.
        val ref = view.frames().first()
        assertFormatError(NwfFormatError.DANGLING_REFERENCE) {
            view.payload(ref.copy(header = ref.header.copy(z = ref.header.z + 1)))
        }
    }

    @Test
    fun `an out-of-order SORTED segment fails closed`() {
        val written = NwfV1SegmentCodec.write(frames().take(2))
        val offsets = written.frameOffsets.values.sorted()
        val bytes = written.bytes()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(offsets[1] + 16, Int.MIN_VALUE) // second frame's x now sorts first
        assertFormatError(NwfFormatError.UNSORTED_INDEX) { parse(bytes) }
    }

    @Test
    fun `transit frames round trip and carry the DataVersion`() {
        for (frame in frames()) {
            val transit = NwfV1TransitCodec.toTransit(frame)
            assertEquals(NwfV1TransitCodec.ENVELOPE_BYTES + frame.header.storedLength, transit.remaining())
            val back = NwfV1TransitCodec.fromTransit(transit)
            assertEquals(frame.header, back.header)
            assertEquals(frame.dataVersion, back.dataVersion)
            assertArrayEquals(frame.payloadView().toBytes(), back.payloadView().toBytes())
            assertEquals(0, transit.position(), "fromTransit must not move the caller's buffer")
        }
    }

    @Test
    fun `damaged transit frames are refused`() {
        val frame = frames().first()
        val bytes = NwfV1TransitCodec.toTransit(frame).toBytes()
        fun from(b: ByteArray) = NwfV1TransitCodec.fromTransit(ByteBuffer.wrap(b))
        assertFormatError(NwfFormatError.BAD_MAGIC) { from(bytes.copyOf().also { it[0] = 0 }) }
        assertFormatError(NwfFormatError.UNSUPPORTED_MAJOR) { from(bytes.copyOf().also { it[2] = 2 }) }
        assertFormatError(NwfFormatError.TRUNCATED) { from(bytes.copyOf(bytes.size - 1)) }
        assertFormatError(NwfFormatError.TRUNCATED) { from(bytes.copyOf(10)) }
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { from(bytes + byteArrayOf(0)) }
        assertFormatError(NwfFormatError.CRC_MISMATCH) { from(bytes.flipBit((bytes.size - 1) * 8)) }
        assertFormatError(NwfFormatError.UNKNOWN_CODEC) { from(bytes.copyOf().also { it[10] = 9 }) }
        assertFormatError(NwfFormatError.BAD_MAGIC) { from(ByteArray(40)) }
        // A frame read from a segment is a valid transit payload source too.
        val key: ChunkKey = frame.key
        assertEquals(key, from(bytes).key)
    }
}
