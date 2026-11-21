package gg.netherite.world.nwf.convert

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfDictionary
import gg.netherite.world.nwf.RootDraft
import gg.netherite.world.nwf.RootView
import gg.netherite.world.nwf.WorldHeader
import gg.netherite.world.nwf.WrittenSegment
import java.nio.ByteBuffer

/**
 * Where a converter puts a world, chunk by chunk, so nothing holds the whole world on heap.
 *
 * Contract: [header] exactly once, before the first [chunk]; each [ChunkKey] at most once; [nbt] is uncompressed
 * binary vanilla NBT exactly as `NbtIo.write` would produce it, carrying its own `DataVersion` (and, for CHUNK, its
 * own `xPos` / `zPos`). The sink may consume [nbt]; callers never reuse the buffer.
 */
interface ChunkNbtSink {
    fun header(header: WorldHeader)

    fun chunk(key: ChunkKey, nbt: ByteBuffer)
}

/** Placement and encoding settings for [NwfWorldBuilder]. */
data class WorldBuildOptions(
    val contentClass: String,
    val segmentPrefix: String,
    val saveLevel: Int,
    val lightPolicy: LightPolicy,
    val inlineBudgetBytes: Long,
    val segmentTargetBytes: Long,
    /** Dictionaries by kind for this class; the builder records them in DICTS. */
    val dictionaries: Map<ChunkKind, NwfDictionary> = emptyMap(),
    /**
     * The base snapshot this world falls through to (HAS_BASE), or null. With a base the built root's DICTS and
     * dimensions start with the base's, so frame `dim` and `dictSlot` may differ from the converter's numbering.
     */
    val base: RootView? = null,
    /**
     * The most compressed frame bytes the builder may hold before [NwfWorldBuilder.finish]. A chunk that would take it
     * past this fails with [gg.netherite.world.nwf.NwfFormatException] ([gg.netherite.world.nwf.NwfFormatError.
     * LIMIT_EXCEEDED]) and the builder is spent. Peak heap is about twice this at [NwfWorldBuilder.finish], when the
     * placed INLINE or segment bytes are assembled next to the held frames.
     */
    val maxHeldBytes: Long = DEFAULT_MAX_HELD_BYTES,
    /**
     * Packed inline (SPEC 3.8): when the world's frames decode to at most this many bytes in all and fit inline, every
     * frame is stored RAW and INLINE is one zstd stream at [packInlineLevel]. 0 (the default) never packs.
     */
    val packInlineMaxRawBytes: Long = 0,
    val packInlineLevel: Int = DEFAULT_PACK_INLINE_LEVEL,
) {
    companion object {
        /** 256 MiB of compressed frames: well above any world a single root and its segments are meant for. */
        const val DEFAULT_MAX_HELD_BYTES: Long = 256L * 1024 * 1024

        /** Default zstd level of a packed INLINE stream (SPEC.md 3.8). */
        const val DEFAULT_PACK_INLINE_LEVEL: Int = 3
    }
}

/**
 * A complete new world: root draft plus the segments it references, which must be stored before the root.
 * When produced by a builder that already serialized [root], [rootBytes] / [rootBytesView] expose those bytes so
 * callers do not need to serialize [root] a second time.
 */
class BuiltWorld private constructor(
    val root: RootDraft,
    val segments: List<WrittenSegment>,
    private val serializedRoot: ByteArray?,
    @Suppress("UNUSED_PARAMETER") owned: Boolean,
) {
    constructor(root: RootDraft, segments: List<WrittenSegment>) : this(root, segments, null, owned = true)

    constructor(root: RootDraft, segments: List<WrittenSegment>, rootBytes: ByteArray?) : this(
        root = root,
        segments = segments,
        serializedRoot = rootBytes?.copyOf(),
        owned = true,
    )

    /** A copy of the already-serialized root bytes, or null when not pre-serialized. */
    fun rootBytes(): ByteArray? = serializedRoot?.copyOf()

    /** A read-only view of the already-serialized root bytes without copying, or null when not pre-serialized. */
    fun rootBytesView(): ByteBuffer? = serializedRoot?.let { ByteBuffer.wrap(it).asReadOnlyBuffer() }

    operator fun component1(): RootDraft = root

    operator fun component2(): List<WrittenSegment> = segments

    fun copy(
        root: RootDraft = this.root,
        segments: List<WrittenSegment> = this.segments,
    ): BuiltWorld = BuiltWorld(root, segments, if (root == this.root) serializedRoot else null, owned = true)

    override fun equals(other: Any?): Boolean =
        this === other || (other is BuiltWorld && root == other.root && segments == other.segments)

    override fun hashCode(): Int = 31 * root.hashCode() + segments.hashCode()

    override fun toString(): String = "BuiltWorld(root=$root, segments=$segments)"

    internal companion object {
        fun withOwnedRootBytes(root: RootDraft, segments: List<WrittenSegment>, rootBytes: ByteArray): BuiltWorld =
            BuiltWorld(root, segments, rootBytes, owned = true)
    }
}

/**
 * Builds a generation-1 NWF world from a converter's output. Single use: [finish] once, then discard. Frames are
 * encoded as chunks arrive but held until [finish] places them, so memory grows with the world's compressed size,
 * bounded by [WorldBuildOptions.maxHeldBytes].
 */
interface NwfWorldBuilder : ChunkNbtSink {
    fun finish(): BuiltWorld
}

fun interface NwfWorldBuilderFactory {
    fun create(options: WorldBuildOptions): NwfWorldBuilder
}
