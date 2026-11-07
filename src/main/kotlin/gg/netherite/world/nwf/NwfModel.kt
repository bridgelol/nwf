package gg.netherite.world.nwf

import java.nio.ByteBuffer
import java.util.HexFormat

/** The data kind of a frame: the chunk, its entities, or its points of interest. [id] is the stored `kind` byte. */
enum class ChunkKind(val id: Int) {
    CHUNK(0),
    ENTITIES(1),
    POI(2),
    ;

    companion object {
        /** The kind for a stored byte, or null for an unknown one (fail closed). */
        fun fromId(id: Int): ChunkKind? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Frame payload codecs. An unknown codec id is FAIL_CLOSED for that chunk: the backend reports an IO error and
 * the server never overwrites the chunk.
 */
enum class FrameCodec(val id: Int) {
    RAW(0),
    ZSTD(1),

    /** zstd with the dictionary in the frame's `dictSlot`. */
    ZSTD_DICT(2),

    /** zstd with the base snapshot's decoded frame for the same `(dim, kind, x, z)` as a raw-content dictionary. */
    ZSTD_BASE_DELTA(3),
    ;

    companion object {
        fun fromId(id: Int): FrameCodec? = entries.firstOrNull { it.id == id }
    }
}

/** Index entry and frame header flag bits. Unknown bits must be zero. */
object EntryFlags {
    /** Deleted: the chunk has no data of this kind, and the base entry is hidden. */
    const val TOMBSTONE: Int = 1 shl 0
    const val LIGHT_STRIPPED: Int = 1 shl 1
    const val HEIGHTMAPS_STRIPPED: Int = 1 shl 2
    const val BASE_DELTA: Int = 1 shl 3
    const val KNOWN: Int = TOMBSTONE or LIGHT_STRIPPED or HEIGHTMAPS_STRIPPED or BASE_DELTA
}

/** Light handling of CHUNK frames, HEADER field 27. [id] is the stored enum value. */
enum class LightPolicy(val id: Int) {
    /** Light is stored as the server wrote it. */
    KEEP(0),

    /** Stored light is dropped on encode; the server relights the chunk on load. */
    STRIP(1),
    ;

    companion object {
        /** The policy for a stored value, or null for an unknown one (fail closed). */
        fun fromId(id: Int): LightPolicy? = entries.firstOrNull { it.id == id }
    }
}

/** Heightmap handling of CHUNK frames, HEADER field 28. [id] is the stored enum value. */
enum class HeightmapPolicy(val id: Int) {
    /** Heightmaps are stored as the server wrote them. */
    KEEP(0),

    /** `Heightmaps` is dropped on encode; the server recomputes missing heightmaps on load. */
    STRIP(1),
    ;

    companion object {
        /** The policy for a stored value, or null for an unknown one (fail closed). */
        fun fromId(id: Int): HeightmapPolicy? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One frame's identity: dimension index (into `WorldHeader.dimensions`), kind and chunk coordinates.
 *
 * Natural order is the INDEX order `(dim, kind, x>>5, z>>5, x, z)` (arithmetic shift, so negative chunks group by
 * their floor region). Writers must emit entries strictly ascending in this order; readers reject anything else.
 */
data class ChunkKey(val dim: Int, val kind: ChunkKind, val x: Int, val z: Int) : Comparable<ChunkKey> {
    init {
        require(dim in 0..MAX_DIM) { "dim $dim outside 0..$MAX_DIM" }
    }

    override fun compareTo(other: ChunkKey): Int = COMPARATOR.compare(this, other)

    companion object {
        const val MAX_DIM: Int = 0xFF
        private const val REGION_SHIFT = 5

        private val COMPARATOR: Comparator<ChunkKey> = compareBy<ChunkKey> { it.dim }
            .thenBy { it.kind.id }
            .thenBy { it.x shr REGION_SHIFT }
            .thenBy { it.z shr REGION_SHIFT }
            .thenBy { it.x }
            .thenBy { it.z }
    }
}

/** A SHA-256 digest. Immutable: the bytes are copied in and out. */
class Sha256(bytes: ByteArray) {
    private val value: ByteArray = bytes.copyOf()

    init {
        require(value.size == BYTES) { "SHA-256 has ${value.size} bytes, expected $BYTES" }
    }

    /** Lower-case hex, the form used in object keys. */
    val hex: String get() = HexFormat.of().formatHex(value)

    fun bytes(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is Sha256 && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = hex

    companion object {
        const val BYTES: Int = 32

        fun ofHex(hex: String): Sha256 = Sha256(HexFormat.of().parseHex(hex))
    }
}

/**
 * One INDEX entry (36 bytes on disk). u32 fields are held in [Int] as their bit pattern; the format limits keep every
 * offset and length non-negative. [crc32c] is the CRC32C of the stored payload, checked before decompression.
 *
 * [location] is the segment index, or [NwfFormat.NONE_U16] for INLINE. For an inline entry [offset] is relative to the
 * INLINE body and points at the payload (inline frames have no header); for a segment entry it points at the frame
 * header and the payload follows it.
 */
data class IndexEntry(
    val x: Int,
    val z: Int,
    val dim: Int,
    val kind: ChunkKind,
    val codec: FrameCodec,
    val flags: Int,
    val location: Int,
    val dictSlot: Int,
    val offset: Int,
    val storedLength: Int,
    val rawLength: Int,
    val crc32c: Int,
    val dataVersion: Int,
) {
    val key: ChunkKey get() = ChunkKey(dim, kind, x, z)
    val isInline: Boolean get() = location == NwfFormat.NONE_U16
    val isTombstone: Boolean get() = flags and EntryFlags.TOMBSTONE != 0
    val hasDictionary: Boolean get() = dictSlot != NwfFormat.NONE_U16
}

/** One TOC entry of a root (32 bytes). [rawLength] is the TOC's u32 as read, never negative. */
data class TocEntry(
    val type: Int,
    val flags: Int,
    val dictSlot: Int,
    val crc32c: Int,
    val rawLength: Long,
    val offset: Long,
    val storedLength: Long,
) {
    val isOptional: Boolean get() = flags and NwfFormat.TOC_FLAG_OPTIONAL != 0
}

/** Root section types. An unknown type is skipped when OPTIONAL, otherwise the root fails closed. */
enum class SectionType(val id: Int, val required: Boolean) {
    HEADER(1, true),
    DICTS(2, false),
    SEGMENTS(3, false),
    INDEX(4, true),
    INLINE(5, false),
    STATS(6, false),
    ;

    companion object {
        fun fromId(id: Int): SectionType? = entries.firstOrNull { it.id == id }
    }
}

/** A DICTS entry; its position in the section is the `dictSlot`. */
data class DictRef(val zstdDictId: Int, val sha256: Sha256)

/**
 * A SEGMENTS entry; its position in the section is the segment index. [salt] is opaque to the format: a store may use
 * it to name a second copy of the same segment (SPEC.md, storage guidance); 0 means none.
 */
data class SegmentRef(val sha256: Sha256, val salt: Int, val length: Long, val frameCount: Int)

/** A segment frame header (28 bytes). It duplicates the index data so fsck can rebuild an index from segments. */
data class FrameHeader(
    val codec: FrameCodec,
    val flags: Int,
    val rawLength: Int,
    val storedLength: Int,
    val crc32c: Int,
    val x: Int,
    val z: Int,
    val dim: Int,
    val kind: ChunkKind,
    val dictSlot: Int,
) {
    val key: ChunkKey get() = ChunkKey(dim, kind, x, z)
}

/**
 * An encoded frame: header, the chunk's `DataVersion` (stored in the index entry, not the frame header) and the stored
 * payload. Immutable: [payload] is read-only and nobody may change its content; readers take [payloadView].
 */
class EncodedFrame(val header: FrameHeader, val dataVersion: Int, payload: ByteBuffer) {
    private val bytes: ByteBuffer = payload.asReadOnlyBuffer()

    init {
        require(bytes.remaining() == header.storedLength) {
            "payload has ${bytes.remaining()} bytes, header says ${header.storedLength}"
        }
    }

    val key: ChunkKey get() = header.key

    /** An independent read-only view of the payload (position 0, limit = storedLength). */
    fun payloadView(): ByteBuffer = bytes.duplicate()
}
