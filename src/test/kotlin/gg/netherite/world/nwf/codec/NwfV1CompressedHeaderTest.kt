package gg.netherite.world.nwf.codec

import gg.netherite.world.nwf.ChunkKey
import gg.netherite.world.nwf.ChunkKind
import gg.netherite.world.nwf.LightPolicy
import gg.netherite.world.nwf.NwfFormat
import gg.netherite.world.nwf.NwfFormatError
import gg.netherite.world.nwf.RootDraft
import gg.netherite.world.nwf.SectionType
import gg.netherite.world.nwf.SegmentFetcher
import gg.netherite.world.nwf.WorldHeader
import gg.netherite.world.nwf.convert.WorldBuildOptions
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID

/**
 * HEADER and STATS are stored as one plain zstd stream (TOC flag ZSTD, no dictionary) when that is smaller (SPEC 3.2).
 * A small world's HEADER (game rules, world PDC, source user data) is most of its root, so this is most of its size.
 */
class NwfV1CompressedHeaderTest {
    private val pipeline = ZstdFramePipeline()
    private val writer = NwfV1RootCodec()

    private fun tocOf(bytes: ByteArray, type: SectionType) =
        writer.parse(ByteBuffer.wrap(bytes)).toc.single { it.type == type.id }

    @Test
    fun `a realistic HEADER is stored zstd, round trips, and is much smaller`() {
        val header = realisticHeader()
        val raw = NwfV1RootCodec.encodeHeader(header)
        val bytes = writer.write(draft(header))
        val view = writer.parse(ByteBuffer.wrap(bytes))
        assertEquals(header, view.header)
        val toc = tocOf(bytes, SectionType.HEADER)
        assertEquals(NwfFormat.TOC_FLAG_ZSTD, toc.flags)
        assertEquals(NwfFormat.NONE_U16, toc.dictSlot)
        assertEquals(raw.size.toLong(), toc.rawLength)
        // The section CRC covers the stored (compressed) bytes, as for INDEX and a packed INLINE.
        val stored = bytes.copyOfRange(toc.offset.toInt(), (toc.offset + toc.storedLength).toInt())
        assertEquals(crc(stored), toc.crc32c)
        assertArrayEquals(raw, ZstdSupport.decompress(stored, raw.size, "HEADER"))
        // Measured: the HEADER of a converted small world is about 5.1 KB raw; it must at least halve.
        assertTrue(raw.size in 4_000..7_000, "realistic HEADER of ${raw.size} bytes")
        assertTrue(toc.storedLength * 2 < raw.size, "HEADER ${raw.size} B -> ${toc.storedLength} B")
        println("HEADER ${raw.size} B -> ${toc.storedLength} B; root ${bytes.size} B")
        // Deterministic.
        assertArrayEquals(bytes, writer.write(draft(header)))
    }

    @Test
    fun `a HEADER that does not shrink stays uncompressed`() {
        val tiny = WorldHeader(writer = "w", dimensions = listOf("d"))
        val bytes = writer.write(draft(tiny))
        assertEquals(0, tocOf(bytes, SectionType.HEADER).flags)
        assertEquals(tiny, writer.parse(ByteBuffer.wrap(bytes)).header)
    }

    @Test
    fun `STATS is compressed when smaller and stays OPTIONAL, small STATS stays raw`() {
        val stats = ByteArrayOutputStream()
            .apply { repeat(200) { write(byteArrayOf(8, 1, 16, 2, 24, 3)) } }
            .toByteArray()
        val bytes = writer.write(draft(header()).copy(stats = stats))
        val toc = tocOf(bytes, SectionType.STATS)
        assertEquals(NwfFormat.TOC_FLAG_OPTIONAL or NwfFormat.TOC_FLAG_ZSTD, toc.flags)
        assertTrue(toc.storedLength < stats.size)
        assertArrayEquals(stats, writer.parse(ByteBuffer.wrap(bytes)).stats())

        val small = writer.write(draft(header()).copy(stats = byteArrayOf(8, 1)))
        assertEquals(NwfFormat.TOC_FLAG_OPTIONAL, tocOf(small, SectionType.STATS).flags)
    }

    @Test
    fun `a root from the new writer reads back through readers built with any options`() {
        val header = realisticHeader()
        val draft = draft(header).copy(headerLevel = 19)
        val bytes = NwfV1RootCodec(RootCodecOptions(sectionLevel = 1)).write(draft)
        val readers = listOf(
            NwfV1RootCodec(),
            NwfV1RootCodec(RootCodecOptions(packInlineMaxRawBytes = RootCodecOptions.PACK_INLINE_FLIP_RAW_BYTES)),
            NwfV1RootCodec(RootCodecOptions(sectionLevel = 22, packInlineLevel = 1)),
        )
        for (reader in readers) {
            val view = reader.parse(ByteBuffer.wrap(bytes))
            assertEquals(header, view.header)
            assertEquals(draft.entries, view.entries().toList())
        }
    }

    @Test
    fun `the draft's header level is used, else the codec's section level, and an invalid one is refused`() {
        val header = realisticHeader()
        val fast = writer.write(draft(header).copy(headerLevel = 1))
        val strong = writer.write(draft(header).copy(headerLevel = 19))
        assertTrue(tocOf(strong, SectionType.HEADER).storedLength <= tocOf(fast, SectionType.HEADER).storedLength)
        val viaCodec = NwfV1RootCodec(RootCodecOptions(sectionLevel = 19)).write(draft(header))
        assertEquals(tocOf(strong, SectionType.HEADER), tocOf(viaCodec, SectionType.HEADER))
        assertFormatError(NwfFormatError.OUT_OF_BOUNDS) { writer.write(draft(header).copy(headerLevel = 99)) }
    }

    @Test
    fun `a built world takes the save level for its HEADER and fsck is clean on it`() {
        val factory = NwfV1WorldBuilderFactory(pipeline, writer)
        val builder = factory.create(
            WorldBuildOptions(
                contentClass = "example",
                segmentPrefix = "example-prefix",
                saveLevel = 7,
                lightPolicy = LightPolicy.STRIP,
                inlineBudgetBytes = 16L * 1024 * 1024,
                segmentTargetBytes = 64 * 1024,
            ),
        )
        val header = realisticHeader()
        builder.header(header)
        sampleNbt().forEach { (key, nbt) -> builder.chunk(key, ByteBuffer.wrap(nbt)) }
        val built = builder.finish()
        assertEquals(7, built.root.headerLevel)
        val bytes = writer.write(built.root)
        assertEquals(NwfFormat.TOC_FLAG_ZSTD, tocOf(bytes, SectionType.HEADER).flags)
        val noSegments = SegmentFetcher { _, _ -> null }
        val report = NwfV1Fsck(pipeline, writer).check(ByteBuffer.wrap(bytes), noSegments, NO_DICTS)
        assertTrue(report.clean, report.issues.toString())
        assertEquals(sampleNbt().size, report.framesDecoded)
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    private fun draft(header: WorldHeader): RootDraft {
        val key = ChunkKey(0, ChunkKind.POI, 0, 0)
        val frame = pipeline.encode(key, ByteBuffer.wrap(nbtBytes(TestChunks.poi(1))), options())
        val inline = frame.payloadView().toBytes()
        val entries = listOf(entryOf(frame, NwfFormat.NONE_U16, 0))
        return RootDraft(0, 1, header, emptyList(), emptyList(), entries, ByteBuffer.wrap(inline))
    }

    /**
     * A HEADER shaped like a converted small world's: a level state with every game rule as name/value strings, a world
     * PDC of plugin keys (NBT), source user data, meta, UUID and the converter's text fields.
     */
    private fun realisticHeader(): WorldHeader {
        val level = Proto().apply {
            varint(1, 1_234_567)
            varint(2, 9_876_543)
            varint(5, 54_321)
            bytes(8, Proto().apply { fixed64(1, 0.0); fixed64(3, 59_999_968.0); fixed64(4, 0.2) }.toByteArray())
            for ((name, value) in GAME_RULES) {
                bytes(9, Proto().string(1, name).string(2, value).toByteArray())
            }
        }.toByteArray()
        val pdc = nbtBytes(
            compound(
                *(0 until 24).map { i ->
                    "example-plugin:key_$i" to when (i % 4) {
                        0 -> TString(UUID(i.toLong(), i * 31L).toString())
                        1 -> TLong(1_700_000_000_000L + i * 1_000L)
                        2 -> compound("level" to TInt(i), "unlocked" to TByte(1), "name" to TString("upgrade_$i"))
                        else -> TIntArray(IntArray(8) { it * i })
                    }
                }.toTypedArray(),
                "example-plugin:members" to stringList((0 until 12).map { UUID(it.toLong(), 7L).toString() }),
            ),
        )
        val userData = ByteArrayOutputStream().apply {
            repeat(20) { i ->
                val owner = UUID(0, i.toLong())
                val json = "{\"region\":\"r$i\",\"flags\":[\"build\",\"break\",\"interact\"],\"owner\":\"$owner\"}"
                write(json.toByteArray())
            }
        }.toByteArray()
        return WorldHeader(
            meta = Proto().apply { fixed64(1, 0.5); fixed64(2, 100.0); fixed64(3, 0.5); varint(6, 2) }.toByteArray(),
            worldUuidBytes = ByteArray(16) { (it * 17).toByte() },
            worldPdc = pdc,
            levelState = level,
            dataVersion = TestChunks.DATA_VERSION,
            writer = "example-writer/0123456789",
            contentClass = "example",
            createdAtMs = 1_700_000_000_000,
            committedAtMs = 1_700_000_100_000,
            dimensions = listOf("minecraft:overworld"),
            lightPolicy = LightPolicy.STRIP,
            converter = "src1->nwf1/0123456789",
            sourceUserData = userData,
            segmentPrefix = "example/segments/0123456789abcdef",
            saveId = "save-0123456789abcdef",
        )
    }

    /** A minimal protobuf writer for the fixture's opaque host fields. */
    private class Proto {
        private val out = ByteArrayOutputStream()

        private fun raw(v: Long) {
            var x = v
            while (x and 0x7FL.inv() != 0L) {
                out.write(((x and 0x7F) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }

        fun varint(field: Int, v: Long) = apply { raw((field shl 3).toLong()); raw(v) }

        fun fixed64(field: Int, v: Double) = apply {
            raw(((field shl 3) or 1).toLong())
            val bits = v.toRawBits()
            for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xFF).toInt())
        }

        fun bytes(field: Int, v: ByteArray) = apply {
            raw(((field shl 3) or 2).toLong())
            raw(v.size.toLong())
            out.write(v)
        }

        fun string(field: Int, v: String) = bytes(field, v.toByteArray())

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private companion object {
        val GAME_RULES: List<Pair<String, String>> = listOf(
            "announceAdvancements" to "true", "blockExplosionDropDecay" to "true", "commandBlockOutput" to "true",
            "commandModificationBlockLimit" to "32768", "disableElytraMovementCheck" to "false",
            "disablePlayerMovementCheck" to "false", "disableRaids" to "false", "doDaylightCycle" to "true",
            "doEntityDrops" to "true", "doFireTick" to "true", "doImmediateRespawn" to "false", "doInsomnia" to "true",
            "doLimitedCrafting" to "false", "doMobLoot" to "true", "doMobSpawning" to "true",
            "doPatrolSpawning" to "true", "doTileDrops" to "true", "doTraderSpawning" to "true",
            "doVinesSpread" to "true", "doWardenSpawning" to "true", "doWeatherCycle" to "true",
            "drowningDamage" to "true", "enderPearlsVanishOnDeath" to "true", "fallDamage" to "true",
            "fireDamage" to "true", "forgiveDeadPlayers" to "true", "freezeDamage" to "true",
            "globalSoundEvents" to "true", "keepInventory" to "false", "lavaSourceConversion" to "false",
            "logAdminCommands" to "true", "maxCommandChainLength" to "65536", "maxCommandForkCount" to "65536",
            "maxEntityCramming" to "24", "mobExplosionDropDecay" to "true", "mobGriefing" to "true",
            "naturalRegeneration" to "true", "playersNetherPortalCreativeDelay" to "1",
            "playersNetherPortalDefaultDelay" to "80", "playersSleepingPercentage" to "100",
            "projectilesCanBreakBlocks" to "true", "randomTickSpeed" to "3", "reducedDebugInfo" to "false",
            "sendCommandFeedback" to "true", "showDeathMessages" to "true", "snowAccumulationHeight" to "1",
            "spawnChunkRadius" to "2", "spawnRadius" to "10", "spectatorsGenerateChunks" to "true",
            "tntExplodes" to "true", "tntExplosionDropDecay" to "false", "universalAnger" to "false",
            "waterSourceConversion" to "true", "allowFireTicksAwayFromPlayer" to "false",
            "minecartMaxSpeed" to "8", "spawnerBlocksEnabled" to "true", "locatorBar" to "true", "pvp" to "true",
        )
    }
}
