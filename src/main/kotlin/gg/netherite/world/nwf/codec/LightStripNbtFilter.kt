package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.EntryFlags
import gg.netherite.world.nwf.FilteredNbt
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NbtStreamFilter
import gg.netherite.world.nwf.NwfFormatError
import java.nio.ByteBuffer

/**
 * The streaming light filter over uncompressed binary NBT (big-endian, named root compound). It walks
 * the tag stream once without building a tree and copies every byte except the tags it drops, so everything else
 * comes out byte-identical, in the same order.
 *
 * For CHUNK with [LightPolicy.STRIP] it drops, from each compound of the root `sections` list, `BlockLight`,
 * `SkyLight`, `starlight.blocklight_state` and `starlight.skylight_state`, and from the root `isLightOn` and
 * `starlight.light_version`. Without `isLightOn` the server treats the chunk as unlit and relights it. With
 * [HeightmapPolicy.STRIP] it drops the root `Heightmaps`. Heightmaps are kept by default.
 *
 * A pre-1.18 chunk (root `Level` compound) passes through unchanged and is not flagged as stripped: the server's data
 * fixer upgrades it on load and owns its light. ENTITIES and POI always pass through unchanged.
 *
 * Every pass also reads the root `DataVersion` (0 when absent) and the chunk's own coordinates: CHUNK `xPos`/`zPos`
 * (inside `Level` for a pre-1.18 chunk), ENTITIES `Position`, and for POI the chunk of each record's block `pos`
 * (`Sections.<y>.Records[].pos`). The pipeline refuses a CHUNK or ENTITIES frame whose coordinates differ from its
 * key, and fsck reports one as corruption. POI record positions are not checked by the pipeline (the server never
 * validates them, and a save must not fail on them); fsck reports a record outside the key's chunk as a warning.
 * Malformed NBT (unknown tag type, truncation, trailing bytes, negative lengths, nesting deeper than [MAX_DEPTH]) fails
 * with [NwfFormatError.BAD_PAYLOAD].
 */
object LightStripNbtFilter : NbtStreamFilter {
    /** The deepest compound / list nesting accepted, as the vanilla NBT reader allows. */
    const val MAX_DEPTH: Int = 512

    override fun filter(
        kind: ChunkKind,
        nbt: ByteBuffer,
        light: LightPolicy,
        heightmaps: HeightmapPolicy,
    ): FilteredNbt =
        scan(kind, nbt.copyRemaining(), light, heightmaps).filtered

    /** As [filter] on an array the caller hands over, plus the coordinates the NBT carries. */
    internal fun scan(kind: ChunkKind, nbt: ByteArray, light: LightPolicy, heightmaps: HeightmapPolicy): NbtScan {
        val stripLight = kind == ChunkKind.CHUNK && light == LightPolicy.STRIP
        val stripHeightmaps = kind == ChunkKind.CHUNK && heightmaps == HeightmapPolicy.STRIP
        return NbtWalker(nbt, kind, stripLight, stripHeightmaps).run()
    }
}

/**
 * A filter pass: the output and what the NBT says about its own chunk. [x]/[z] are null when the NBT carries no
 * coordinates (a POI chunk without records). [mixedPositions] is true when POI records lie in more than one chunk.
 */
internal class NbtScan(
    val filtered: FilteredNbt,
    val x: Int?,
    val z: Int?,
    val legacyLayout: Boolean,
    val mixedPositions: Boolean = false,
)

private object NbtTags {
    const val END = 0
    const val BYTE = 1
    const val SHORT = 2
    const val INT = 3
    const val LONG = 4
    const val FLOAT = 5
    const val DOUBLE = 6
    const val BYTE_ARRAY = 7
    const val STRING = 8
    const val LIST = 9
    const val COMPOUND = 10
    const val INT_ARRAY = 11
    const val LONG_ARRAY = 12

    // Tag names are ASCII, so their modified UTF-8 form is their ASCII bytes.
    val SECTIONS = "sections".toByteArray(Charsets.US_ASCII)
    val LEVEL = "Level".toByteArray(Charsets.US_ASCII)
    val DATA_VERSION = "DataVersion".toByteArray(Charsets.US_ASCII)
    val X_POS = "xPos".toByteArray(Charsets.US_ASCII)
    val Z_POS = "zPos".toByteArray(Charsets.US_ASCII)
    val POSITION = "Position".toByteArray(Charsets.US_ASCII)
    val POI_SECTIONS = "Sections".toByteArray(Charsets.US_ASCII)
    val RECORDS = "Records".toByteArray(Charsets.US_ASCII)
    val POS = "pos".toByteArray(Charsets.US_ASCII)
    val HEIGHTMAPS = "Heightmaps".toByteArray(Charsets.US_ASCII)
    val ROOT_LIGHT = listOf("isLightOn", "starlight.light_version").map { it.toByteArray(Charsets.US_ASCII) }
    val SECTION_LIGHT = listOf("BlockLight", "SkyLight", "starlight.blocklight_state", "starlight.skylight_state")
        .map { it.toByteArray(Charsets.US_ASCII) }

    /** Payload size of a fixed-size tag, or -1. */
    fun fixedSize(type: Int): Int = when (type) {
        BYTE -> 1
        SHORT -> 2
        INT, FLOAT -> 4
        LONG, DOUBLE -> 8
        else -> -1
    }
}

private class NbtWalker(
    private val b: ByteArray,
    private val kind: ChunkKind,
    private val stripLight: Boolean,
    private val stripHeightmaps: Boolean,
) {
    private var pos = 0

    /** Bytes before [copyFrom] are already in [out]; a drop copies up to the dropped tag and skips it. */
    private var copyFrom = 0
    private var out: LeWriter? = null

    private var dataVersion = 0
    private var x: Int? = null
    private var z: Int? = null
    private var legacy = false
    private var mixed = false

    fun run(): NbtScan {
        val rootType = u8()
        if (rootType != NbtTags.COMPOUND) bad("root tag type $rootType, expected a compound")
        skip(u16())
        walkRoot()
        if (pos != b.size) bad("${b.size - pos} trailing bytes after the root compound")
        // A pre-1.18 chunk (root `Level`) passes through byte-identical, whatever tags were dropped before `Level`
        // was seen: the server's data fixer owns it.
        if (legacy) return NbtScan(FilteredNbt(ByteBuffer.wrap(b), dataVersion, 0), x, z, legacy, mixed)
        val bytes = out?.let {
            it.bytes(b, copyFrom, b.size - copyFrom)
            it.toByteArray()
        } ?: b
        var flags = 0
        if (stripLight && !legacy) flags = flags or EntryFlags.LIGHT_STRIPPED
        if (stripHeightmaps && !legacy) flags = flags or EntryFlags.HEIGHTMAPS_STRIPPED
        return NbtScan(FilteredNbt(ByteBuffer.wrap(bytes), dataVersion, flags), x, z, legacy, mixed)
    }

    private fun walkRoot() {
        while (true) {
            val tagStart = pos
            val type = u8()
            if (type == NbtTags.END) return
            val nameStart = pos + 2
            val nameLength = u16()
            skip(nameLength)
            when {
                stripLight && NbtTags.ROOT_LIGHT.any { nameIs(nameStart, nameLength, it) } -> {
                    skipPayload(type, 1)
                    drop(tagStart)
                }
                stripHeightmaps && nameIs(nameStart, nameLength, NbtTags.HEIGHTMAPS) -> {
                    skipPayload(type, 1)
                    drop(tagStart)
                }
                stripLight && type == NbtTags.LIST && nameIs(nameStart, nameLength, NbtTags.SECTIONS) -> {
                    walkSections()
                }
                type == NbtTags.INT && nameIs(nameStart, nameLength, NbtTags.DATA_VERSION) -> {
                    dataVersion = i32()
                }
                kind == ChunkKind.CHUNK && type == NbtTags.INT && nameIs(nameStart, nameLength, NbtTags.X_POS) -> {
                    x = i32()
                }
                kind == ChunkKind.CHUNK && type == NbtTags.INT && nameIs(nameStart, nameLength, NbtTags.Z_POS) -> {
                    z = i32()
                }
                kind == ChunkKind.ENTITIES && type == NbtTags.INT_ARRAY &&
                    nameIs(nameStart, nameLength, NbtTags.POSITION) -> {
                    readPosition()
                }
                kind == ChunkKind.CHUNK && type == NbtTags.COMPOUND && nameIs(nameStart, nameLength, NbtTags.LEVEL) -> {
                    legacy = true
                    walkLevel()
                }
                kind == ChunkKind.POI && type == NbtTags.COMPOUND &&
                    nameIs(nameStart, nameLength, NbtTags.POI_SECTIONS) -> {
                    walkPoiSections()
                }
                else -> {
                    if (type == NbtTags.COMPOUND && nameIs(nameStart, nameLength, NbtTags.LEVEL)) legacy = true
                    skipPayload(type, 1)
                }
            }
        }
    }

    /** The root `sections` list: light tags are dropped from each section compound. */
    private fun walkSections() {
        val elementType = u8()
        val count = i32()
        if (count < 0) bad("negative list length $count")
        if (elementType != NbtTags.COMPOUND) {
            skipElements(elementType, count, 2)
            return
        }
        repeat(count) {
            while (true) {
                val tagStart = pos
                val type = u8()
                if (type == NbtTags.END) break
                val nameStart = pos + 2
                val nameLength = u16()
                skip(nameLength)
                skipPayload(type, 3)
                if (NbtTags.SECTION_LIGHT.any { nameIs(nameStart, nameLength, it) }) drop(tagStart)
            }
        }
    }

    /** A pre-1.18 `Level` compound: reads `xPos`/`zPos`, skips everything else, drops nothing. */
    private fun walkLevel() {
        forEachTag(2) { type, nameStart, nameLength ->
            when {
                type == NbtTags.INT && nameIs(nameStart, nameLength, NbtTags.X_POS) -> x = i32()
                type == NbtTags.INT && nameIs(nameStart, nameLength, NbtTags.Z_POS) -> z = i32()
                else -> skipPayload(type, 2)
            }
        }
    }

    /** POI `Sections`: each section's `Records[].pos` gives the chunk the records lie in. Drops nothing. */
    private fun walkPoiSections() {
        forEachTag(2) { sectionType, _, _ ->
            if (sectionType != NbtTags.COMPOUND) {
                skipPayload(sectionType, 2)
                return@forEachTag
            }
            forEachTag(3) { type, nameStart, nameLength ->
                if (type == NbtTags.LIST && nameIs(nameStart, nameLength, NbtTags.RECORDS)) {
                    walkPoiRecords()
                } else {
                    skipPayload(type, 3)
                }
            }
        }
    }

    private fun walkPoiRecords() {
        val elementType = u8()
        val count = length()
        if (elementType != NbtTags.COMPOUND) {
            skipElements(elementType, count, 4)
            return
        }
        repeat(count) {
            forEachTag(5) { type, nameStart, nameLength ->
                if (type == NbtTags.INT_ARRAY && nameIs(nameStart, nameLength, NbtTags.POS)) {
                    readPoiPos()
                } else {
                    skipPayload(type, 5)
                }
            }
        }
    }

    private fun readPoiPos() {
        val length = length()
        if (length != POI_POS_INTS) {
            skip(length.toLong() * 4)
            return
        }
        val chunkX = i32() shr CHUNK_SHIFT
        i32()
        val chunkZ = i32() shr CHUNK_SHIFT
        if (x == null) {
            x = chunkX
            z = chunkZ
        } else if (x != chunkX || z != chunkZ) {
            mixed = true
        }
    }

    /** Walks the entries of the compound at [pos] up to its END tag; [body] must consume each entry's payload. */
    private inline fun forEachTag(depth: Int, body: (type: Int, nameStart: Int, nameLength: Int) -> Unit) {
        if (depth > LightStripNbtFilter.MAX_DEPTH) bad("NBT nested deeper than ${LightStripNbtFilter.MAX_DEPTH}")
        while (true) {
            val type = u8()
            if (type == NbtTags.END) return
            val nameStart = pos + 2
            val nameLength = u16()
            skip(nameLength)
            body(type, nameStart, nameLength)
        }
    }

    private fun readPosition() {
        val length = i32()
        if (length < 0) bad("negative array length $length")
        if (length == 2) {
            x = i32()
            z = i32()
        } else {
            skip(length.toLong() * 4)
        }
    }

    private fun skipPayload(type: Int, depth: Int) {
        if (depth > LightStripNbtFilter.MAX_DEPTH) bad("NBT nested deeper than ${LightStripNbtFilter.MAX_DEPTH}")
        val fixed = NbtTags.fixedSize(type)
        if (fixed > 0) {
            skip(fixed)
            return
        }
        when (type) {
            NbtTags.BYTE_ARRAY -> skip(length().toLong())
            NbtTags.INT_ARRAY -> skip(length().toLong() * 4)
            NbtTags.LONG_ARRAY -> skip(length().toLong() * 8)
            NbtTags.STRING -> skip(u16())
            NbtTags.LIST -> skipElements(u8(), length(), depth + 1)
            NbtTags.COMPOUND -> skipCompound(depth + 1)
            else -> bad("unknown tag type $type at ${pos - 1}")
        }
    }

    private fun skipCompound(depth: Int) {
        while (true) {
            val type = u8()
            if (type == NbtTags.END) return
            skip(u16())
            skipPayload(type, depth)
        }
    }

    private fun skipElements(elementType: Int, count: Int, depth: Int) {
        if (count == 0) return
        if (elementType == NbtTags.END) bad("list of $count elements without an element type")
        val fixed = NbtTags.fixedSize(elementType)
        if (fixed > 0) {
            skip(count.toLong() * fixed)
            return
        }
        repeat(count) { skipPayload(elementType, depth) }
    }

    private fun drop(tagStart: Int) {
        val sink = out ?: LeWriter(b.size).also { out = it }
        sink.bytes(b, copyFrom, tagStart - copyFrom)
        copyFrom = pos
    }

    private fun nameIs(start: Int, length: Int, name: ByteArray): Boolean {
        if (length != name.size) return false
        for (i in name.indices) if (b[start + i] != name[i]) return false
        return true
    }

    private fun length(): Int {
        val n = i32()
        if (n < 0) bad("negative length $n")
        return n
    }

    private fun need(n: Long) {
        if (n < 0 || n > b.size - pos) bad("truncated NBT at $pos (needs $n more bytes)")
    }

    private fun skip(n: Int) = skip(n.toLong())

    private fun skip(n: Long) {
        need(n)
        pos += n.toInt()
    }

    private fun u8(): Int {
        need(1)
        return b[pos++].toInt() and U8_MASK
    }

    private fun u16(): Int {
        need(2)
        val v = ((b[pos].toInt() and U8_MASK) shl 8) or (b[pos + 1].toInt() and U8_MASK)
        pos += 2
        return v
    }

    private fun i32(): Int {
        need(4)
        var v = 0
        repeat(4) { v = (v shl 8) or (b[pos++].toInt() and U8_MASK) }
        return v
    }

    private fun bad(message: String): Nothing = nwfError(NwfFormatError.BAD_PAYLOAD, "$kind NBT: $message")

    private companion object {
        const val POI_POS_INTS = 3
        const val CHUNK_SHIFT = 4
    }
}
