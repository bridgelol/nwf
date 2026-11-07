package gg.netherite.world.nwf

/**
 * Constants of the NWF v1 byte format (SPEC.md). They are part of the stored format: never change a value; a
 * breaking change bumps [MAJOR].
 *
 * All integers are little-endian. Strings are u16 length + UTF-8. Hashes are SHA-256 (32 bytes). Checksums are CRC32C
 * (`java.util.zip.CRC32C`).
 */
object NwfFormat {
    /** Root object magic "NWFR". */
    val ROOT_MAGIC: ByteArray = byteArrayOf(0x4E, 0x57, 0x46, 0x52)

    /** Segment object magic "NWFS". */
    val SEGMENT_MAGIC: ByteArray = byteArrayOf(0x4E, 0x57, 0x46, 0x53)

    /** A reader refuses any other major (FAIL_CLOSED). */
    const val MAJOR: Int = 1

    /** A reader accepts a higher minor and skips unknown OPTIONAL sections. */
    const val MINOR: Int = 0

    /** Bytes needed to classify an object by magic. */
    const val SNIFF_LENGTH: Int = 4

    // Root
    const val ROOT_HEADER_BYTES: Int = 32
    const val TOC_ENTRY_BYTES: Int = 32
    const val MIN_SECTIONS: Int = 1
    const val MAX_SECTIONS: Int = 32

    /** Root flag: immutable base snapshot of a template. */
    const val ROOT_FLAG_TEMPLATE: Int = 1 shl 0

    /** Root flag: lookups fall through to `WorldHeader.baseRootSha`. A base must not itself have a base. */
    const val ROOT_FLAG_HAS_BASE: Int = 1 shl 1
    const val ROOT_FLAGS_KNOWN: Int = ROOT_FLAG_TEMPLATE or ROOT_FLAG_HAS_BASE

    /** TOC entry flags. */
    const val TOC_FLAG_OPTIONAL: Int = 1 shl 0
    const val TOC_FLAG_ZSTD: Int = 1 shl 1
    const val TOC_FLAG_DICT: Int = 1 shl 2

    // Section bodies

    /** DICTS entry: u32 zstdDictId + 32 B sha256. */
    const val DICT_ENTRY_BYTES: Int = 36

    /** SEGMENTS entry: 32 B sha256 + u32 salt + u64 length + u32 frameCount. */
    const val SEGMENT_ENTRY_BYTES: Int = 48

    /** INDEX entry. */
    const val INDEX_ENTRY_BYTES: Int = 36

    /** `IndexEntry.location` / `TocEntry.dictSlot` / `IndexEntry.dictSlot` sentinel: inline, or no dictionary. */
    const val NONE_U16: Int = 0xFFFF

    // Segment
    const val SEGMENT_HEADER_BYTES: Int = 32
    const val SEGMENT_TRAILER_BYTES: Int = 4
    const val SEGMENT_FLAG_SORTED: Int = 1 shl 0

    /** Frame header marker "NF" (u16 0x464E). */
    const val FRAME_MARKER: Int = 0x464E

    /** Segment frame header: marker, codec, flags, rawLength, storedLength, crc32c, x, z, dim, kind, dictSlot. */
    const val FRAME_HEADER_BYTES: Int = 28

    // Limits
    const val MAX_ROOT_BYTES: Long = 64L * 1024 * 1024
    const val MAX_SEGMENT_BYTES: Long = 64L * 1024 * 1024
    const val TARGET_SEGMENT_BYTES: Long = 4L * 1024 * 1024
    const val MAX_FRAME_RAW_BYTES: Long = 256L * 1024 * 1024

    /** All section bodies of one root together, decoded; checked from the TOC before any body is decompressed. */
    const val MAX_ROOT_RAW_BYTES: Long = 256L * 1024 * 1024
    const val MAX_ENTRIES: Long = 1L shl 31

    /** A short format tag for stores that label objects (for example as object metadata). */
    const val FORMAT_METADATA_VALUE: String = "nwf1"

    /** True when [prefix] starts with [ROOT_MAGIC]. Fewer than [SNIFF_LENGTH] bytes is never a root. */
    fun isRoot(prefix: ByteArray): Boolean = startsWith(prefix, ROOT_MAGIC)

    /** True when [prefix] starts with [SEGMENT_MAGIC]. */
    fun isSegment(prefix: ByteArray): Boolean = startsWith(prefix, SEGMENT_MAGIC)

    private fun startsWith(prefix: ByteArray, magic: ByteArray): Boolean =
        prefix.size >= magic.size && magic.indices.all { prefix[it] == magic[it] }
}
