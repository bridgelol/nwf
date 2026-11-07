package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.HeaderField
import gg.netherite.world.nwf.HeightmapPolicy
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.WorldHeader
import java.io.ByteArrayOutputStream

/**
 * The HEADER protobuf (SPEC.md, "HEADER"), written and read without a protobuf library.
 *
 * Writer: fields in ascending number order, unknown fields merged in by number (stable). A bytes field is always
 * written, even when empty; a string, integer or enum field only when it differs from its default (empty, 0); an
 * optional field (4, 8) only when present; each dimension as one field 24. Integers are plain varints, a negative
 * value as its 10-byte two's complement.
 *
 * Reader: any field order; a repeated scalar keeps the last value; an absent bytes field reads as empty. A wrong wire
 * type for a known field, an unknown enum value, a group, a truncated field or invalid UTF-8 fails with
 * [NwfFormatError.INVALID_HEADER].
 */
internal object NwfHeaderProto {
    private const val F_META = 1
    private const val F_WORLD_UUID = 2
    private const val F_WORLD_PDC = 3
    private const val F_LEVEL_STATE = 4
    private const val F_DATA_VERSION = 5
    private const val F_WRITER = 6
    private const val F_ORIGIN = 7
    private const val F_LEGACY_ETAG = 8
    private const val F_CONTENT_DIGEST = 9
    private const val F_CONTENT_CLASS = 20
    private const val F_PARENT_ROOT_SHA = 21
    private const val F_CREATED_AT = 22
    private const val F_COMMITTED_AT = 23
    private const val F_DIMENSIONS = 24
    private const val F_BASE_ROOT_SHA = 25
    private const val F_BASE_KEY = 26
    private const val F_LIGHT_POLICY = 27
    private const val F_HEIGHTMAP_POLICY = 28
    private const val F_CONVERTER = 29
    private const val F_SOURCE_USER_DATA = 30
    private const val F_SEGMENT_PREFIX = 31
    private const val F_SAVE_ID = 32

    private const val TAG_SHIFT = 3
    private const val WIRE_MASK = 7
    private const val SEVEN_BITS = 0x7F
    private const val CONTINUATION = 0x80
    private const val MAX_VARINT_BYTES = 10
    private const val I64_BYTES = 8
    private const val I32_BYTES = 4
    private const val UUID_BYTES = 16

    /** At most one dimension per `dim` byte value. */
    const val MAX_DIMENSIONS: Int = 256

    /** Unknown (newer-minor) fields kept per HEADER; far above any real use, low enough to bound decoding. */
    const val MAX_UNKNOWN_FIELDS: Int = 1024

    fun encode(header: WorldHeader): ByteArray {
        val known = ByteArrayOutputStream()
        val out = Writer(known)
        val unknown = header.unknownFields.sortedBy { it.number }
        var next = 0

        /** Writes every unknown field numbered below [number] (all of them for [Int.MAX_VALUE]). */
        fun flushUnknownBelow(number: Int) {
            while (next < unknown.size && unknown[next].number < number) out.raw(unknown[next++])
        }

        fun field(number: Int, write: () -> Unit) {
            flushUnknownBelow(number)
            write()
        }
        field(F_META) { out.bytes(F_META, header.meta) }
        field(F_WORLD_UUID) { out.bytes(F_WORLD_UUID, header.worldUuidBytes) }
        field(F_WORLD_PDC) { out.bytes(F_WORLD_PDC, header.worldPdc) }
        field(F_LEVEL_STATE) { header.levelState?.let { out.bytes(F_LEVEL_STATE, it) } }
        field(F_DATA_VERSION) { out.int(F_DATA_VERSION, header.dataVersion.toLong()) }
        field(F_WRITER) { out.string(F_WRITER, header.writer) }
        field(F_ORIGIN) { out.int(F_ORIGIN, header.originId.toLong()) }
        field(F_LEGACY_ETAG) { header.legacyEtag?.let { out.bytes(F_LEGACY_ETAG, it.toByteArray(Charsets.UTF_8)) } }
        field(F_CONTENT_DIGEST) { out.bytes(F_CONTENT_DIGEST, header.contentDigest) }
        field(F_CONTENT_CLASS) { out.string(F_CONTENT_CLASS, header.contentClass) }
        field(F_PARENT_ROOT_SHA) { out.bytes(F_PARENT_ROOT_SHA, header.parentRootSha) }
        field(F_CREATED_AT) { out.int(F_CREATED_AT, header.createdAtMs) }
        field(F_COMMITTED_AT) { out.int(F_COMMITTED_AT, header.committedAtMs) }
        field(F_DIMENSIONS) {
            for (d in header.dimensions) out.bytes(F_DIMENSIONS, d.toByteArray(Charsets.UTF_8))
        }
        field(F_BASE_ROOT_SHA) { out.bytes(F_BASE_ROOT_SHA, header.baseRootSha) }
        field(F_BASE_KEY) { out.string(F_BASE_KEY, header.baseKey) }
        field(F_LIGHT_POLICY) { out.int(F_LIGHT_POLICY, header.lightPolicy.id.toLong()) }
        field(F_HEIGHTMAP_POLICY) { out.int(F_HEIGHTMAP_POLICY, header.heightmapPolicy.id.toLong()) }
        field(F_CONVERTER) { out.string(F_CONVERTER, header.converter) }
        field(F_SOURCE_USER_DATA) { out.bytes(F_SOURCE_USER_DATA, header.sourceUserData) }
        field(F_SEGMENT_PREFIX) { out.string(F_SEGMENT_PREFIX, header.segmentPrefix) }
        field(F_SAVE_ID) { out.string(F_SAVE_ID, header.saveId) }
        flushUnknownBelow(Int.MAX_VALUE)
        return known.toByteArray()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun decode(bytes: ByteArray): WorldHeader {
        val r = Reader(bytes)
        val v = Values()
        while (r.hasMore()) {
            val tag = r.varint()
            val number = (tag ushr TAG_SHIFT).toInt()
            val wire = (tag and WIRE_MASK.toLong()).toInt()
            if (tag ushr TAG_SHIFT > HeaderField.MAX_FIELD_NUMBER || number < 1) invalid("field number in tag $tag")
            when (number) {
                F_META -> v.meta = r.len(number, wire)
                F_WORLD_UUID -> v.worldUuid = checkedUuid(r.len(number, wire))
                F_WORLD_PDC -> v.worldPdc = r.len(number, wire)
                F_LEVEL_STATE -> v.levelState = r.len(number, wire)
                F_DATA_VERSION -> v.dataVersion = r.int32(number, wire)
                F_WRITER -> v.writer = r.string(number, wire)
                F_ORIGIN -> v.origin = r.int32(number, wire)
                F_LEGACY_ETAG -> v.legacyEtag = r.string(number, wire)
                F_CONTENT_DIGEST -> v.contentDigest = r.len(number, wire)
                F_CONTENT_CLASS -> v.contentClass = r.string(number, wire)
                F_PARENT_ROOT_SHA -> v.parentRootSha = r.len(number, wire)
                F_CREATED_AT -> v.createdAt = r.int64(number, wire)
                F_COMMITTED_AT -> v.committedAt = r.int64(number, wire)
                F_DIMENSIONS -> v.addDimension(r.string(number, wire))
                F_BASE_ROOT_SHA -> v.baseRootSha = r.len(number, wire)
                F_BASE_KEY -> v.baseKey = r.string(number, wire)
                F_LIGHT_POLICY -> v.light = r.lightPolicy(number, wire)
                F_HEIGHTMAP_POLICY -> v.heightmaps = r.heightmapPolicy(number, wire)
                F_CONVERTER -> v.converter = r.string(number, wire)
                F_SOURCE_USER_DATA -> v.sourceUserData = r.len(number, wire)
                F_SEGMENT_PREFIX -> v.segmentPrefix = r.string(number, wire)
                F_SAVE_ID -> v.saveId = r.string(number, wire)
                else -> v.addUnknown(HeaderField(number, wire, r.unknownValue(number, wire)))
            }
        }
        return WorldHeader(
            meta = v.meta,
            worldUuidBytes = v.worldUuid,
            worldPdc = v.worldPdc,
            levelState = v.levelState,
            dataVersion = v.dataVersion,
            writer = v.writer,
            originId = v.origin,
            legacyEtag = v.legacyEtag,
            contentDigest = v.contentDigest,
            contentClass = v.contentClass,
            parentRootSha = v.parentRootSha,
            createdAtMs = v.createdAt,
            committedAtMs = v.committedAt,
            dimensions = v.dimensions,
            baseRootSha = v.baseRootSha,
            baseKey = v.baseKey,
            lightPolicy = v.light,
            heightmapPolicy = v.heightmaps,
            converter = v.converter,
            sourceUserData = v.sourceUserData,
            segmentPrefix = v.segmentPrefix,
            saveId = v.saveId,
            unknownFields = v.unknown,
        )
    }

    private fun checkedUuid(uuid: ByteArray): ByteArray {
        if (uuid.isNotEmpty() && uuid.size != UUID_BYTES) invalid("world_uuid of ${uuid.size} bytes, not 0 or 16")
        return uuid
    }

    private fun invalid(detail: String): Nothing = nwfError(NwfFormatError.INVALID_HEADER, "HEADER protobuf: $detail")

    /** Decoded field values, defaults until a field is seen. */
    private class Values {
        var meta = ByteArray(0)
        var worldUuid = ByteArray(0)
        var worldPdc = ByteArray(0)
        var levelState: ByteArray? = null
        var dataVersion = 0
        var writer = ""
        var origin = 0
        var legacyEtag: String? = null
        var contentDigest = ByteArray(0)
        var contentClass = ""
        var parentRootSha = ByteArray(0)
        var createdAt = 0L
        var committedAt = 0L
        val dimensions = ArrayList<String>()
        var baseRootSha = ByteArray(0)
        var baseKey = ""
        var light = LightPolicy.KEEP
        var heightmaps = HeightmapPolicy.KEEP
        var converter = ""
        var sourceUserData = ByteArray(0)
        var segmentPrefix = ""
        var saveId = ""
        val unknown = ArrayList<HeaderField>()

        // Counted as each field arrives, so a hostile HEADER cannot build a huge list before any check runs.
        fun addDimension(dimension: String) {
            if (dimensions.size == MAX_DIMENSIONS) invalid("more than $MAX_DIMENSIONS dimensions")
            dimensions += dimension
        }

        fun addUnknown(field: HeaderField) {
            if (unknown.size == MAX_UNKNOWN_FIELDS) invalid("more than $MAX_UNKNOWN_FIELDS unknown fields")
            unknown += field
        }
    }

    private class Writer(private val out: ByteArrayOutputStream) {
        fun bytes(number: Int, value: ByteArray) {
            tag(number, HeaderField.LEN)
            varint(value.size.toLong())
            out.write(value)
        }

        fun string(number: Int, value: String) {
            if (value.isNotEmpty()) bytes(number, value.toByteArray(Charsets.UTF_8))
        }

        fun int(number: Int, value: Long) {
            if (value == 0L) return
            tag(number, HeaderField.VARINT)
            varint(value)
        }

        fun raw(field: HeaderField) {
            tag(field.number, field.wireType)
            val value = field.value()
            if (field.wireType == HeaderField.LEN) varint(value.size.toLong())
            out.write(value)
        }

        private fun tag(number: Int, wire: Int) = varint((number.toLong() shl TAG_SHIFT) or wire.toLong())

        private fun varint(value: Long) {
            var v = value
            while (v and SEVEN_BITS.toLong().inv() != 0L) {
                out.write(((v and SEVEN_BITS.toLong()).toInt()) or CONTINUATION)
                v = v ushr 7
            }
            out.write(v.toInt())
        }
    }

    private class Reader(private val b: ByteArray) {
        private var pos = 0

        fun hasMore(): Boolean = pos < b.size

        fun varint(): Long {
            var result = 0L
            for (i in 0 until MAX_VARINT_BYTES) {
                if (pos >= b.size) invalid("truncated varint")
                val byte = b[pos++].toInt() and U8_MASK
                result = result or ((byte and SEVEN_BITS).toLong() shl (7 * i))
                if (byte and CONTINUATION == 0) return result
            }
            invalid("varint longer than $MAX_VARINT_BYTES bytes")
        }

        fun len(number: Int, wire: Int): ByteArray {
            expect(number, wire, HeaderField.LEN)
            return lengthDelimited()
        }

        fun string(number: Int, wire: Int): String {
            val raw = len(number, wire)
            val decoder = Charsets.UTF_8.newDecoder()
            return try {
                decoder.decode(java.nio.ByteBuffer.wrap(raw)).toString()
            } catch (e: java.nio.charset.CharacterCodingException) {
                invalid("field $number is not UTF-8: ${e.message}")
            }
        }

        fun int32(number: Int, wire: Int): Int {
            expect(number, wire, HeaderField.VARINT)
            return varint().toInt()
        }

        fun int64(number: Int, wire: Int): Long {
            expect(number, wire, HeaderField.VARINT)
            return varint()
        }

        fun lightPolicy(number: Int, wire: Int): LightPolicy {
            val id = int32(number, wire)
            return LightPolicy.fromId(id) ?: invalid("field $number: unknown light policy $id")
        }

        fun heightmapPolicy(number: Int, wire: Int): HeightmapPolicy {
            val id = int32(number, wire)
            return HeightmapPolicy.fromId(id) ?: invalid("field $number: unknown heightmap policy $id")
        }

        private fun varintBytes(): ByteArray {
            val start = pos
            varint()
            return b.copyOfRange(start, pos)
        }

        /** The stored value of an unknown field (see [HeaderField]). */
        fun unknownValue(number: Int, wire: Int): ByteArray = when (wire) {
            HeaderField.VARINT -> varintBytes()
            HeaderField.I64 -> fixed(I64_BYTES, number)
            HeaderField.LEN -> lengthDelimited()
            HeaderField.I32 -> fixed(I32_BYTES, number)
            else -> invalid("field $number has wire type $wire (groups are not allowed)")
        }

        private fun fixed(size: Int, number: Int): ByteArray {
            if (b.size - pos < size) invalid("field $number truncated")
            return b.copyOfRange(pos, pos + size).also { pos += size }
        }

        private fun lengthDelimited(): ByteArray {
            val length = varint()
            if (length < 0 || length > b.size - pos) invalid("length $length beyond ${b.size - pos} bytes")
            return b.copyOfRange(pos, pos + length.toInt()).also { pos += length.toInt() }
        }

        private fun expect(number: Int, wire: Int, expected: Int) {
            if (wire != expected) invalid("field $number has wire type $wire, expected $expected")
        }
    }
}
