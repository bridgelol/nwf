package gg.netherite.world.nwf

import java.nio.ByteBuffer
import java.util.UUID

/**
 * The HEADER section of an NWF root: a protobuf message (SPEC.md, "HEADER"). Field numbers are part of the stored
 * format: add new fields with new numbers, never renumber.
 *
 * Fields 1-9 and 30 are host fields: NWF stores them and copies them from root to root, but gives them no meaning
 * beyond their wire type. [levelState] is an embedded message the host defines, [originId] a host enum value.
 * [unknownFields] keeps any field this version does not know, verbatim, so a root rewritten by an older reader loses
 * nothing.
 *
 * Byte arrays are owned by the instance; never mutate one after construction.
 */
@Suppress("LongParameterList")
class WorldHeader(
    /** 1: host world metadata, opaque. */
    val meta: ByteArray = ByteArray(0),
    /** 2: the world UUID, 16 big-endian bytes, or empty when unknown. See [worldUuid]. */
    val worldUuidBytes: ByteArray = ByteArray(0),
    /** 3: the world's persistent data container, opaque. */
    val worldPdc: ByteArray = ByteArray(0),
    /** 4: host level state (time, weather, border, game rules), an embedded message; null when absent. */
    val levelState: ByteArray? = null,
    /** 5: the Minecraft data version the world was last written with. */
    val dataVersion: Int = 0,
    /** 6: the writer's identity, free text. */
    val writer: String = "",
    /** 7: how the world came to exist; 0 = written natively, other values are host-defined. */
    val originId: Int = 0,
    /** 8: the version tag (for example an object-store ETag) of the source a converter read, or null. */
    val legacyEtag: String? = null,
    /** 9: an optional content digest so tools can compare worlds. */
    val contentDigest: ByteArray = ByteArray(0),
    /** 20: the content class (a host label for one kind of world); selects dictionaries and the light policy. */
    val contentClass: String = "",
    /** 21: SHA-256 of the previous root's bytes; empty for generation 1. */
    val parentRootSha: ByteArray = ByteArray(0),
    /** 22: creation time, Unix milliseconds. */
    val createdAtMs: Long = 0,
    /** 23: time of the commit that wrote this root, Unix milliseconds. */
    val committedAtMs: Long = 0,
    /** 24: dimension keys (`minecraft:overworld`, ...); the index is the `dim` byte of every frame. */
    val dimensions: List<String> = emptyList(),
    /** 25: the base snapshot root's SHA-256, set together with the HAS_BASE root flag. */
    val baseRootSha: ByteArray = ByteArray(0),
    /** 26: the host's name for the base snapshot, informational. */
    val baseKey: String = "",
    /** 27: how light is stored in CHUNK frames. */
    val lightPolicy: LightPolicy = LightPolicy.KEEP,
    /** 28: how heightmaps are stored in CHUNK frames. */
    val heightmapPolicy: HeightmapPolicy = HeightmapPolicy.KEEP,
    /** 29: the converter that produced the world from another format, or empty for a native world. */
    val converter: String = "",
    /** 30: the source format's world user data, carried verbatim by converters. */
    val sourceUserData: ByteArray = ByteArray(0),
    /** 31: the world-private key prefix of its segments, fixed at creation. */
    val segmentPrefix: String = "",
    /** 32: the host's id of the save that wrote this root. */
    val saveId: String = "",
    /** Fields this version does not know, in stored order. */
    val unknownFields: List<HeaderField> = emptyList(),
) {
    init {
        for (field in unknownFields) {
            require(field.number !in KNOWN_FIELDS) { "field ${field.number} is a known HEADER field" }
        }
    }

    /** The world UUID, or null when the header carries none. Throws [NwfFormatException] for a malformed field 2. */
    val worldUuid: UUID?
        get() = when (worldUuidBytes.size) {
            0 -> null
            UUID_BYTES -> ByteBuffer.wrap(worldUuidBytes).let { UUID(it.getLong(), it.getLong()) }
            else -> throw NwfFormatException(
                NwfFormatError.INVALID_HEADER,
                "worldUuid has ${worldUuidBytes.size} bytes, expected $UUID_BYTES",
            )
        }

    /** A copy with the given fields replaced (the class is not a data class: its byte arrays compare by content). */
    fun copy(
        meta: ByteArray = this.meta,
        worldUuidBytes: ByteArray = this.worldUuidBytes,
        worldPdc: ByteArray = this.worldPdc,
        levelState: ByteArray? = this.levelState,
        dataVersion: Int = this.dataVersion,
        writer: String = this.writer,
        originId: Int = this.originId,
        legacyEtag: String? = this.legacyEtag,
        contentDigest: ByteArray = this.contentDigest,
        contentClass: String = this.contentClass,
        parentRootSha: ByteArray = this.parentRootSha,
        createdAtMs: Long = this.createdAtMs,
        committedAtMs: Long = this.committedAtMs,
        dimensions: List<String> = this.dimensions,
        baseRootSha: ByteArray = this.baseRootSha,
        baseKey: String = this.baseKey,
        lightPolicy: LightPolicy = this.lightPolicy,
        heightmapPolicy: HeightmapPolicy = this.heightmapPolicy,
        converter: String = this.converter,
        sourceUserData: ByteArray = this.sourceUserData,
        segmentPrefix: String = this.segmentPrefix,
        saveId: String = this.saveId,
        unknownFields: List<HeaderField> = this.unknownFields,
    ): WorldHeader = WorldHeader(
        meta, worldUuidBytes, worldPdc, levelState, dataVersion, writer, originId, legacyEtag, contentDigest,
        contentClass, parentRootSha, createdAtMs, committedAtMs, dimensions, baseRootSha, baseKey, lightPolicy,
        heightmapPolicy, converter, sourceUserData, segmentPrefix, saveId, unknownFields,
    )

    private fun fields(): List<Any?> = listOf(
        meta.toList(), worldUuidBytes.toList(), worldPdc.toList(), levelState?.toList(), dataVersion, writer, originId,
        legacyEtag, contentDigest.toList(), contentClass, parentRootSha.toList(), createdAtMs, committedAtMs,
        dimensions, baseRootSha.toList(), baseKey, lightPolicy, heightmapPolicy, converter, sourceUserData.toList(),
        segmentPrefix, saveId, unknownFields,
    )

    override fun equals(other: Any?): Boolean = other is WorldHeader && fields() == other.fields()

    override fun hashCode(): Int = fields().hashCode()

    override fun toString(): String =
        "WorldHeader(contentClass=$contentClass, dimensions=$dimensions, dataVersion=$dataVersion, writer=$writer, " +
            "originId=$originId, converter=$converter, baseKey=$baseKey, light=$lightPolicy, " +
            "heightmaps=$heightmapPolicy, segmentPrefix=$segmentPrefix, saveId=$saveId, meta=${meta.size} B, " +
            "worldPdc=${worldPdc.size} B, levelState=${levelState?.size?.let { "$it B" }}, " +
            "unknownFields=${unknownFields.map { it.number }})"

    companion object {
        private const val UUID_BYTES = 16

        /** The field numbers this version reads into typed properties. */
        val KNOWN_FIELDS: Set<Int> = (1..9).toSet() + (20..32).toSet()

        /** The 16-byte big-endian form of [uuid] for [worldUuidBytes]. */
        fun uuidBytes(uuid: UUID): ByteArray =
            ByteBuffer.allocate(UUID_BYTES)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
    }
}

/**
 * One HEADER field this version does not know: its number, protobuf wire type and value bytes as stored (a varint's
 * bytes, the 8 or 4 fixed bytes, or a length-delimited field's payload without its length prefix). Immutable.
 */
class HeaderField(val number: Int, val wireType: Int, value: ByteArray) {
    private val content: ByteArray = value.copyOf()

    init {
        require(number in 1..MAX_FIELD_NUMBER) { "field number $number" }
        require(wireType in WIRE_TYPES) { "wire type $wireType" }
    }

    fun value(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is HeaderField && number == other.number && wireType == other.wireType &&
            content.contentEquals(other.content)

    override fun hashCode(): Int = (number * 31 + wireType) * 31 + content.contentHashCode()

    override fun toString(): String = "HeaderField(number=$number, wireType=$wireType, ${content.size} B)"

    companion object {
        const val VARINT: Int = 0
        const val I64: Int = 1
        const val LEN: Int = 2
        const val I32: Int = 5
        const val MAX_FIELD_NUMBER: Int = (1 shl 29) - 1
        private val WIRE_TYPES = setOf(VARINT, I64, LEN, I32)
    }
}
