package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.EncodedFrame
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.FrameHeader
import gg.netherite.world.nwf.IndexEntry
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.NwfSegmentCodec
import gg.netherite.world.nwf.SegmentFrameRef
import gg.netherite.world.nwf.SegmentView
import gg.netherite.world.nwf.Sha256
import gg.netherite.world.nwf.WrittenSegment
import java.nio.ByteBuffer

/**
 * NWF v1 segment reader and writer. Stateless and thread-safe.
 *
 * The reader checks magic, major, size limit, the optional whole-object SHA-256, the trailer CRC over the 32-byte
 * header, reserved bytes, known flags, every frame marker and length (frames tile the body exactly, `frameCount` of
 * them) and, for SORTED segments, strict key order. Payload bytes are not checked here: each frame's CRC32C is checked
 * before decompression. A frame header with an unknown codec fails closed for that frame only
 * ([SegmentView.frames], [NwfV1SegmentView.header]).
 */
object NwfV1SegmentCodec : NwfSegmentCodec {
    private const val OFF_MAJOR = 4
    private const val OFF_FRAME_COUNT = 8
    private const val OFF_FLAGS = 12
    private const val OFF_RESERVED = 16
    private const val RESERVED_BYTES = 16
    private const val SEGMENT_FLAGS_KNOWN = NwfFormat.SEGMENT_FLAG_SORTED

    override fun parse(bytes: ByteBuffer, expectedSha: Sha256?): NwfV1SegmentView {
        val copy = bytes.copyRemaining()
        return failClosed("segment") { parseCopy(copy, expectedSha) }
    }

    override fun write(frames: List<EncodedFrame>): WrittenSegment {
        val sorted = frames.sortedBy { it.key }
        for (i in 1 until sorted.size) {
            require(sorted[i - 1].key != sorted[i].key) { "frame ${sorted[i].key} given twice" }
        }
        val total = NwfFormat.SEGMENT_HEADER_BYTES.toLong() + NwfFormat.SEGMENT_TRAILER_BYTES +
            sorted.sumOf { NwfFormat.FRAME_HEADER_BYTES.toLong() + it.header.storedLength }
        if (total > NwfFormat.MAX_SEGMENT_BYTES) nwfError(NwfFormatError.LIMIT_EXCEEDED, "segment of $total bytes")
        val out = LeWriter(total.toInt())
        out.bytes(NwfFormat.SEGMENT_MAGIC).u16(NwfFormat.MAJOR).u16(NwfFormat.MINOR)
            .i32(sorted.size).i32(NwfFormat.SEGMENT_FLAG_SORTED).bytes(ByteArray(RESERVED_BYTES))
        val offsets = LinkedHashMap<ChunkKey, Int>(sorted.size * 2)
        for (frame in sorted) {
            NwfFrameHeaders.checkConsistent(frame.header, "frame ${frame.key}")
            offsets[frame.key] = out.size
            NwfFrameHeaders.write(out, frame.header)
            out.bytes(frame.payloadView())
        }
        val headerBytes = out.toByteArray().copyOf(NwfFormat.SEGMENT_HEADER_BYTES)
        out.i32(crc32c(headerBytes))
        val bytes = out.toByteArray()
        return WrittenSegment(bytes, sha256(bytes), offsets)
    }

    private fun parseCopy(bytes: ByteArray, expectedSha: Sha256?): NwfV1SegmentView {
        val b = bytes.littleEndian()
        if (bytes.size < NwfFormat.SNIFF_LENGTH) nwfError(NwfFormatError.TRUNCATED, "${bytes.size} bytes")
        if (!NwfFormat.isSegment(bytes)) nwfError(NwfFormatError.BAD_MAGIC, "not an NWFS segment")
        if (bytes.size < NwfFormat.SEGMENT_HEADER_BYTES + NwfFormat.SEGMENT_TRAILER_BYTES) {
            nwfError(NwfFormatError.TRUNCATED, "${bytes.size} bytes")
        }
        val major = b.u16(OFF_MAJOR)
        if (major != NwfFormat.MAJOR) nwfError(NwfFormatError.UNSUPPORTED_MAJOR, "major $major")
        if (bytes.size > NwfFormat.MAX_SEGMENT_BYTES) nwfError(NwfFormatError.LIMIT_EXCEEDED, "${bytes.size} bytes")
        val sha = sha256(bytes)
        if (expectedSha != null && sha != expectedSha) {
            nwfError(NwfFormatError.SHA_MISMATCH, "segment sha $sha, expected $expectedSha")
        }
        val trailerAt = bytes.size - NwfFormat.SEGMENT_TRAILER_BYTES
        if (crc32c(bytes, 0, NwfFormat.SEGMENT_HEADER_BYTES) != b.getInt(trailerAt)) {
            nwfError(NwfFormatError.CRC_MISMATCH, "segment trailer CRC")
        }
        val flags = b.getInt(OFF_FLAGS)
        if (flags and SEGMENT_FLAGS_KNOWN.inv() != 0) nwfError(NwfFormatError.UNKNOWN_FLAGS, "segment flags $flags")
        if ((0 until RESERVED_BYTES).any { bytes[OFF_RESERVED + it].toInt() != 0 }) {
            nwfError(NwfFormatError.UNKNOWN_FLAGS, "segment reserved bytes")
        }
        val count = b.u32(OFF_FRAME_COUNT)
        val maxFrames = (trailerAt - NwfFormat.SEGMENT_HEADER_BYTES) / NwfFormat.FRAME_HEADER_BYTES
        if (count > maxFrames) nwfError(NwfFormatError.TRUNCATED, "frameCount $count in ${bytes.size} bytes")
        val offsets = IntArray(count.toInt())
        var pos = NwfFormat.SEGMENT_HEADER_BYTES
        for (i in offsets.indices) {
            if (pos + NwfFormat.FRAME_HEADER_BYTES > trailerAt) nwfError(NwfFormatError.TRUNCATED, "frame $i header")
            if (b.u16(pos) != NwfFormat.FRAME_MARKER) nwfError(NwfFormatError.OUT_OF_BOUNDS, "frame $i marker")
            val stored = b.u32(pos + 8)
            if (b.u8(pos + 3) and EntryFlags.KNOWN.inv() != 0) {
                nwfError(NwfFormatError.UNKNOWN_FLAGS, "frame $i flags")
            }
            if (stored > trailerAt - pos - NwfFormat.FRAME_HEADER_BYTES) {
                nwfError(NwfFormatError.TRUNCATED, "frame $i payload of $stored bytes")
            }
            offsets[i] = pos
            pos += NwfFormat.FRAME_HEADER_BYTES + stored.toInt()
        }
        if (pos != trailerAt) nwfError(NwfFormatError.OUT_OF_BOUNDS, "frames end at $pos, trailer at $trailerAt")
        if (flags and NwfFormat.SEGMENT_FLAG_SORTED != 0) checkSorted(b, offsets)
        return NwfV1SegmentView(ByteBuffer.wrap(bytes).asReadOnlyBuffer(), flags, offsets, sha)
    }

    private fun checkSorted(b: ByteBuffer, offsets: IntArray) {
        for (i in 1 until offsets.size) {
            val p = offsets[i - 1]
            val q = offsets[i]
            val c = RootLayout.compareKeys(
                b.u8(p + 24), b.u8(p + 25), b.getInt(p + 16), b.getInt(p + 20),
                b.u8(q + 24), b.u8(q + 25), b.getInt(q + 16), b.getInt(q + 20),
            )
            if (c >= 0) nwfError(NwfFormatError.UNSORTED_INDEX, "SORTED segment: frame $i out of order")
        }
    }
}

/** A parsed segment. Holds its own copy of the bytes. */
class NwfV1SegmentView internal constructor(
    private val bytes: ByteBuffer,
    override val flags: Int,
    private val offsets: IntArray,
    override val sha256: Sha256,
) : SegmentView {
    override val frameCount: Int get() = offsets.size

    /** Length of the segment object in bytes. */
    val length: Long get() = bytes.capacity().toLong()

    /** Header offsets of every frame in stored order. */
    fun headerOffsets(): List<Int> = offsets.toList()

    override fun frames(): Sequence<SegmentFrameRef> = offsets.asSequence().map { SegmentFrameRef(it, headerAt(it)) }

    /**
     * The frame header [entry] points at, checked against [entry] in every field (see [NwfFrameHeaders.readFor]).
     * Unknown codec or kind fails closed for that frame.
     */
    fun header(entry: IndexEntry): FrameHeader {
        requireFrame(entry.offset)
        return NwfFrameHeaders.readFor(bytes, entry.offset, entry)
    }

    override fun payload(entry: IndexEntry): ByteBuffer = payloadAt(entry.offset, header(entry))

    override fun payload(frame: SegmentFrameRef): ByteBuffer {
        val stored = headerAt(frame.headerOffset)
        if (stored != frame.header) {
            nwfError(NwfFormatError.DANGLING_REFERENCE, "frame ref at ${frame.headerOffset} does not match the segment")
        }
        return payloadAt(frame.headerOffset, stored)
    }

    /** The frame header at [headerOffset], unchecked against any index entry (fsck reports disagreement itself). */
    internal fun headerAt(headerOffset: Int): FrameHeader {
        requireFrame(headerOffset)
        return NwfFrameHeaders.read(bytes, headerOffset)
    }

    private fun payloadAt(headerOffset: Int, header: FrameHeader): ByteBuffer {
        val start = headerOffset + NwfFormat.FRAME_HEADER_BYTES
        return bytes.duplicate().position(start).limit(start + header.storedLength).slice().asReadOnlyBuffer()
    }

    private fun requireFrame(headerOffset: Int) {
        if (offsets.binarySearch(headerOffset) < 0) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "no frame header at offset $headerOffset")
        }
    }

    override fun toString(): String = "NwfV1SegmentView(frames=$frameCount, bytes=$length, sha=$sha256)"
}
