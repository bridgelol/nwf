package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.FrameCodec
import gg.netherite.world.nwf.FrameHeader
import gg.netherite.world.nwf.IndexEntry
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 28-byte segment frame header, shared by segments, the transit envelope and range reads:
 *
 * ```
 * 0  u16 marker 0x464E "NF"   2 u8 codec   3 u8 flags   4 u32 rawLength   8 u32 storedLength   12 u32 crc32c
 * 16 i32 x   20 i32 z   24 u8 dim   25 u8 kind   26 u16 dictSlot
 * ```
 */
object NwfFrameHeaders {
    private const val HEX = 16

    /**
     * Reads the header at [offset] of [buffer] (absolute, little-endian, position untouched). Throws
     * [gg.netherite.world.nwf.NwfFormatException] for a missing marker, an unknown codec, kind or flag bit, or lengths
     * outside the format limits.
     */
    fun read(buffer: ByteBuffer, offset: Int): FrameHeader {
        val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        if (offset < 0 || offset.toLong() + NwfFormat.FRAME_HEADER_BYTES > b.limit()) {
            nwfError(NwfFormatError.TRUNCATED, "frame header at $offset needs ${NwfFormat.FRAME_HEADER_BYTES} bytes")
        }
        val marker = b.u16(offset)
        if (marker != NwfFormat.FRAME_MARKER) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "no frame marker at $offset (0x${marker.toString(HEX)})")
        }
        val codecId = b.u8(offset + 2)
        val codec = FrameCodec.fromId(codecId)
            ?: nwfError(NwfFormatError.UNKNOWN_CODEC, "frame at $offset has codec $codecId")
        val flags = b.u8(offset + 3)
        val rawLength = lengthField(b.u32(offset + 4), "rawLength", offset)
        val storedLength = lengthField(b.u32(offset + 8), "storedLength", offset)
        val kindId = b.u8(offset + 25)
        val kind = ChunkKind.fromId(kindId)
            ?: nwfError(NwfFormatError.OUT_OF_BOUNDS, "frame at $offset has kind $kindId")
        val header = FrameHeader(
            codec = codec,
            flags = flags,
            rawLength = rawLength,
            storedLength = storedLength,
            crc32c = b.getInt(offset + 12),
            x = b.getInt(offset + 16),
            z = b.getInt(offset + 20),
            dim = b.u8(offset + 24),
            kind = kind,
            dictSlot = b.u16(offset + 26),
        )
        checkConsistent(header, "frame at $offset")
        return header
    }

    /**
     * Reads the header at [offset] as [read] does, then checks it against the index [entry] that points at it: every
     * field must agree ([disagreements]), so a stale or damaged index can never serve another chunk's bytes. Throws
     * [gg.netherite.world.nwf.NwfFormatException] with [NwfFormatError.DANGLING_REFERENCE] on any difference. Use it
     * for every read through an index entry, range reads included.
     */
    fun readFor(buffer: ByteBuffer, offset: Int, entry: IndexEntry): FrameHeader {
        if (offset != entry.offset) {
            nwfError(NwfFormatError.DANGLING_REFERENCE, "frame at $offset read for an entry at ${entry.offset}")
        }
        val header = read(buffer, offset)
        val differences = disagreements(header, entry)
        if (differences.isNotEmpty()) {
            nwfError(
                NwfFormatError.DANGLING_REFERENCE,
                "frame at $offset disagrees with its index entry: ${differences.joinToString("; ")}",
            )
        }
        return header
    }

    /** Writes [header] as 28 little-endian bytes. */
    internal fun write(out: LeWriter, header: FrameHeader) {
        out.u16(NwfFormat.FRAME_MARKER)
            .u8(header.codec.id)
            .u8(header.flags)
            .i32(header.rawLength)
            .i32(header.storedLength)
            .i32(header.crc32c)
            .i32(header.x)
            .i32(header.z)
            .u8(header.dim)
            .u8(header.kind.id)
            .u16(header.dictSlot)
    }

    /**
     * Field-by-field differences between a segment frame header and the index entry that points at it. Empty when they
     * agree. Readers treat any difference as corruption.
     */
    fun disagreements(header: FrameHeader, entry: IndexEntry): List<String> = buildList {
        if (header.key != entry.key) add("key ${header.key} != index ${entry.key}")
        if (header.codec != entry.codec) add("codec ${header.codec} != index ${entry.codec}")
        if (header.flags != entry.flags) add("flags ${header.flags} != index ${entry.flags}")
        if (header.rawLength != entry.rawLength) add("rawLength ${header.rawLength} != index ${entry.rawLength}")
        if (header.storedLength != entry.storedLength) {
            add("storedLength ${header.storedLength} != index ${entry.storedLength}")
        }
        if (header.crc32c != entry.crc32c) add("crc32c ${header.crc32c} != index ${entry.crc32c}")
        if (header.dictSlot != entry.dictSlot) add("dictSlot ${header.dictSlot} != index ${entry.dictSlot}")
    }

    /** The header an index entry describes (for building frames from index data). */
    fun of(entry: IndexEntry): FrameHeader = FrameHeader(
        codec = entry.codec,
        flags = entry.flags,
        rawLength = entry.rawLength,
        storedLength = entry.storedLength,
        crc32c = entry.crc32c,
        x = entry.x,
        z = entry.z,
        dim = entry.dim,
        kind = entry.kind,
        dictSlot = entry.dictSlot,
    )

    /**
     * Rules every stored data frame obeys: known flag bits, no tombstone, ZSTD_DICT if and only if a dictionary slot,
     * BASE_DELTA flag if and only if the base-delta codec, RAW stored length equal to its raw length, the size limits.
     */
    internal fun checkConsistent(header: FrameHeader, what: String) {
        if (header.flags and EntryFlags.KNOWN.inv() != 0) {
            nwfError(NwfFormatError.UNKNOWN_FLAGS, "$what has flags 0x${header.flags.toString(HEX)}")
        }
        if (header.flags and EntryFlags.TOMBSTONE != 0) {
            nwfError(NwfFormatError.UNKNOWN_FLAGS, "$what is a tombstone; tombstones are never frames")
        }
        if (header.dim !in 0..ChunkKey.MAX_DIM) nwfError(NwfFormatError.OUT_OF_BOUNDS, "$what has dim ${header.dim}")
        if (header.dictSlot !in 0..U16_MASK) {
            nwfError(NwfFormatError.OUT_OF_BOUNDS, "$what has dictSlot ${header.dictSlot}")
        }
        val usesDict = header.dictSlot != NwfFormat.NONE_U16
        if ((header.codec == FrameCodec.ZSTD_DICT) != usesDict) {
            nwfError(NwfFormatError.DANGLING_REFERENCE, "$what: codec ${header.codec} with dictSlot ${header.dictSlot}")
        }
        val deltaFlag = header.flags and EntryFlags.BASE_DELTA != 0
        if ((header.codec == FrameCodec.ZSTD_BASE_DELTA) != deltaFlag) {
            nwfError(NwfFormatError.UNKNOWN_FLAGS, "$what: codec ${header.codec} with BASE_DELTA flag $deltaFlag")
        }
        if (header.rawLength < 0 || header.rawLength > NwfFormat.MAX_FRAME_RAW_BYTES) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "$what has rawLength ${header.rawLength}")
        }
        if (header.storedLength < 0) {
            nwfError(NwfFormatError.LIMIT_EXCEEDED, "$what has storedLength ${header.storedLength}")
        }
        if (header.codec == FrameCodec.RAW && header.rawLength != header.storedLength) {
            nwfError(
                NwfFormatError.OUT_OF_BOUNDS,
                "$what: RAW frame stores ${header.storedLength} bytes for rawLength ${header.rawLength}",
            )
        }
    }

    private fun lengthField(value: Long, name: String, offset: Int): Int {
        if (value > Int.MAX_VALUE) nwfError(NwfFormatError.LIMIT_EXCEEDED, "frame at $offset has $name $value")
        return value.toInt()
    }
}
