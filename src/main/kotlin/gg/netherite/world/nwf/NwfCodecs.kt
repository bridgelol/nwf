package gg.netherite.world.nwf

import java.nio.ByteBuffer

/**
 * A parsed root. Immutable and thread-safe: readers may share one instance across threads and replace it with one
 * volatile swap when a newer root is committed.
 *
 * A [RootView] exists only for bytes that passed every check: magic, major, known flags, `headerCrc`, every section
 * CRC, TOC bounds without gaps or overlap, required sections present, and a strictly sorted INDEX.
 */
interface RootView {
    val minor: Int
    val flags: Int
    val generation: Long
    val header: WorldHeader
    val dictionaries: List<DictRef>
    val segments: List<SegmentRef>

    /** Number of INDEX entries, tombstones included. */
    val entryCount: Int

    /** SHA-256 of the exact bytes this root was parsed from (the next commit's `parentRootSha`). */
    val rootSha: Sha256

    /** Length of the root object in bytes. */
    val storedLength: Long

    /** Length of the INLINE body in bytes, 0 when absent. */
    val inlineLength: Long

    /**
     * True when INLINE is stored as one zstd stream (packed inline, SPEC 3.8). [inlineLength] is then the decoded
     * length, which the reader decompressed once when it parsed the root.
     */
    val isInlinePacked: Boolean get() = false

    val isTemplate: Boolean get() = flags and NwfFormat.ROOT_FLAG_TEMPLATE != 0
    val hasBase: Boolean get() = flags and NwfFormat.ROOT_FLAG_HAS_BASE != 0

    /** This root's own entry for [key] (no base fall-through), tombstones included. */
    fun find(key: ChunkKey): IndexEntry?

    /** Every own entry in INDEX order. */
    fun entries(): Sequence<IndexEntry>

    /** True when this root has no own entry for any kind of the chunk in [dim] at ([x], [z]). Cheap, no IO. */
    fun hasNoEntries(dim: Int, x: Int, z: Int): Boolean

    /**
     * The inline payload of [entry] as an independent read-only buffer. Throws [NwfFormatException] when [entry] is
     * not inline or not inside INLINE. The CRC is not checked here; frame decoding checks it.
     */
    fun inlinePayload(entry: IndexEntry): ByteBuffer
}

/**
 * The input to [NwfRootCodec.write]. [entries] must be strictly sorted by [ChunkKey]; inline entries' offsets point
 * into [inline]. The writer computes `headerCrc`, section CRCs, the TOC, compresses INDEX, and compresses HEADER and
 * STATS when that makes them smaller.
 */
data class RootDraft(
    val flags: Int,
    val generation: Long,
    val header: WorldHeader,
    val dictionaries: List<DictRef>,
    val segments: List<SegmentRef>,
    val entries: List<IndexEntry>,
    val inline: ByteBuffer,
    /** Optional STATS protobuf; null omits the section. */
    val stats: ByteArray? = null,
    /**
     * Packed inline (SPEC 3.8): compress the whole INLINE body as one plain zstd stream at this level when that makes
     * it smaller. The caller decides when a root is packed and makes its inline frames RAW. Null leaves the choice to
     * the codec's own options (off by default).
     */
    val packInlineLevel: Int? = null,
    /**
     * The zstd level for HEADER and STATS (SPEC 3.2): each is stored as one plain zstd stream (no dictionary) when that
     * makes it smaller, else uncompressed. Writers that know the world's save level pass it; null uses the codec's
     * own section level.
     */
    val headerLevel: Int? = null,
)

/** Root reader and writer. Pure: no IO, no shared state. */
interface NwfRootCodec {
    /** Parses and fully verifies a root. Throws only [NwfFormatException]. */
    fun parse(bytes: ByteBuffer): RootView

    /** Serializes [draft]; `parse(write(d))` yields the same content. Throws [NwfFormatException] on a limit breach. */
    fun write(draft: RootDraft): ByteArray
}

/** One frame inside a segment: [headerOffset] is what an [IndexEntry.offset] stores for it. */
data class SegmentFrameRef(val headerOffset: Int, val header: FrameHeader)

/** A parsed segment. Immutable. */
interface SegmentView {
    val flags: Int
    val frameCount: Int

    /** SHA-256 of the whole object: the segment id. */
    val sha256: Sha256

    fun frames(): Sequence<SegmentFrameRef>

    /**
     * The payload of the frame [entry] points at, read-only. The frame header at `entry.offset` is read and must agree
     * with [entry] in every field (key, codec, flags, lengths, CRC, dictionary slot); otherwise, or when no frame
     * starts there, it throws [NwfFormatException] with [NwfFormatError.DANGLING_REFERENCE] or
     * [NwfFormatError.OUT_OF_BOUNDS].
     */
    fun payload(entry: IndexEntry): ByteBuffer

    /** The payload of a frame [frames] returned, read-only; [frame] must match the header stored at its offset. */
    fun payload(frame: SegmentFrameRef): ByteBuffer
}

/** A segment as written: its bytes, id and the header offset of every frame for the new INDEX. */
class WrittenSegment(bytes: ByteArray, val sha256: Sha256, val frameOffsets: Map<ChunkKey, Int>) {
    private val content: ByteArray = bytes.copyOf()

    val length: Long get() = content.size.toLong()

    fun bytes(): ByteArray = content.copyOf()
}

/** Segment reader and writer. Segments are immutable once written; their id is their SHA-256. */
interface NwfSegmentCodec {
    /**
     * Parses a segment. With [expectedSha] (a whole-object GET) the SHA-256 must match; range reads skip this
     * and rely on each frame CRC. Throws only [NwfFormatException].
     */
    fun parse(bytes: ByteBuffer, expectedSha: Sha256?): SegmentView

    /** Writes [frames] sorted by [ChunkKey] (SORTED flag set). Each key at most once. */
    fun write(frames: List<EncodedFrame>): WrittenSegment
}

/**
 * The transit form of a frame between a storage hook's calls in one process (`encode` -> `stage`, `read` -> `decode`):
 * `u16 0x544E "NT"`, `u16 version = 1`, `u32 dataVersion`, the 28-byte segment frame header, the payload. It carries
 * the `DataVersion` the index needs, which the segment frame header does not. Never persisted.
 */
interface TransitFrameCodec {
    fun toTransit(frame: EncodedFrame): ByteBuffer

    /** Throws [NwfFormatException] for anything that is not a transit frame. */
    fun fromTransit(buffer: ByteBuffer): EncodedFrame
}
