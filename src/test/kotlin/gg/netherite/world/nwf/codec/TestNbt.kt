@file:Suppress("MaxLineLength") // dense assertion tables read better unwrapped

package gg.netherite.world.nwf.codec

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.random.Random

/** A minimal binary NBT model for tests: written exactly as the vanilla writer does (big-endian, modified UTF-8). */
sealed class Tag(val id: Int) {
    abstract fun writePayload(out: DataOutputStream)
}

class TByte(val v: Int) : Tag(1) {
    override fun writePayload(out: DataOutputStream) = out.writeByte(v)
}

class TShort(val v: Int) : Tag(2) {
    override fun writePayload(out: DataOutputStream) = out.writeShort(v)
}

class TInt(val v: Int) : Tag(3) {
    override fun writePayload(out: DataOutputStream) = out.writeInt(v)
}

class TLong(val v: Long) : Tag(4) {
    override fun writePayload(out: DataOutputStream) = out.writeLong(v)
}

class TFloat(val v: Float) : Tag(5) {
    override fun writePayload(out: DataOutputStream) = out.writeFloat(v)
}

class TDouble(val v: Double) : Tag(6) {
    override fun writePayload(out: DataOutputStream) = out.writeDouble(v)
}

class TByteArray(val v: ByteArray) : Tag(7) {
    override fun writePayload(out: DataOutputStream) {
        out.writeInt(v.size)
        out.write(v)
    }
}

class TString(val v: String) : Tag(8) {
    override fun writePayload(out: DataOutputStream) = out.writeUTF(v)
}

class TList(val elementType: Int, val items: List<Tag>) : Tag(9) {
    override fun writePayload(out: DataOutputStream) {
        out.writeByte(elementType)
        out.writeInt(items.size)
        items.forEach { it.writePayload(out) }
    }
}

class TCompound(val entries: List<Pair<String, Tag>>) : Tag(10) {
    override fun writePayload(out: DataOutputStream) {
        for ((name, tag) in entries) {
            out.writeByte(tag.id)
            out.writeUTF(name)
            tag.writePayload(out)
        }
        out.writeByte(0)
    }

    fun without(vararg names: String): TCompound = TCompound(entries.filter { it.first !in names })
}

class TIntArray(val v: IntArray) : Tag(11) {
    override fun writePayload(out: DataOutputStream) {
        out.writeInt(v.size)
        v.forEach { out.writeInt(it) }
    }
}

class TLongArray(val v: LongArray) : Tag(12) {
    override fun writePayload(out: DataOutputStream) {
        out.writeInt(v.size)
        v.forEach { out.writeLong(it) }
    }
}

fun compound(vararg entries: Pair<String, Tag>): TCompound = TCompound(entries.toList())

fun list(elementType: Int, items: List<Tag>): TList = TList(elementType, items)

fun compoundList(items: List<TCompound>): TList = TList(10, items)

fun stringList(items: List<String>): TList = TList(8, items.map { TString(it) })

/** The bytes of a named root compound, as the vanilla writer produces them. */
fun nbtBytes(root: TCompound, rootName: String = ""): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.writeByte(10)
        out.writeUTF(rootName)
        root.writePayload(out)
    }
    return bytes.toByteArray()
}

enum class PaletteStyle { EMPTY, SINGLE, FULL }

/** Synthetic chunk, entity and POI NBT in the modern (1.18+) layout. Deterministic for a given seed. */
object TestChunks {
    const val DATA_VERSION: Int = 4440
    private const val SECTIONS = 24
    private const val MIN_SECTION = -4

    private val BLOCKS = listOf(
        "minecraft:stone", "minecraft:dirt", "minecraft:grass_block", "minecraft:oak_log", "minecraft:oak_planks",
        "minecraft:cobblestone", "minecraft:iron_ore", "minecraft:gold_ore", "minecraft:diamond_ore",
        "minecraft:water", "minecraft:lava", "minecraft:sand", "minecraft:gravel", "minecraft:chest",
        "minecraft:furnace", "minecraft:torch", "minecraft:glass", "minecraft:white_wool", "minecraft:hopper",
    )

    @Suppress("LongParameterList")
    fun chunk(
        x: Int,
        z: Int,
        style: PaletteStyle,
        seed: Int,
        sections: Int = SECTIONS,
        light: Boolean = true,
        heightmaps: Boolean = true,
        blockEntities: Int = 2,
        lastUpdate: Long = 1000L + seed,
    ): TCompound {
        val random = Random(seed)
        val entries = ArrayList<Pair<String, Tag>>()
        entries += "DataVersion" to TInt(DATA_VERSION)
        entries += "xPos" to TInt(x)
        entries += "yPos" to TInt(MIN_SECTION)
        entries += "zPos" to TInt(z)
        entries += "Status" to TString("minecraft:full")
        entries += "LastUpdate" to TLong(lastUpdate)
        entries += "sections" to compoundList((0 until sections).map { section(MIN_SECTION + it, style, random, light, seed) })
        if (light) entries += "isLightOn" to TByte(1)
        entries += "block_entities" to compoundList((0 until blockEntities).map { blockEntity(x, z, it, random) })
        if (heightmaps) {
            // Own generators, so leaving light or heightmaps out does not shift any other value.
            val hm = Random(seed * 31 + 7)
            entries += "Heightmaps" to compound(
                "MOTION_BLOCKING" to TLongArray(LongArray(37) { hm.nextLong() }),
                "WORLD_SURFACE" to TLongArray(LongArray(37) { hm.nextLong() }),
            )
        }
        entries += "fluid_ticks" to list(0, emptyList())
        entries += "block_ticks" to compoundList(
            listOf(
                compound(
                    "i" to TString("minecraft:water"),
                    "x" to TInt(x * 16 + 3), "y" to TInt(64), "z" to TInt(z * 16 + 5),
                    "t" to TInt(4), "p" to TInt(0),
                ),
            ),
        )
        entries += "PostProcessing" to list(9, List(sections) { list(2, emptyList()) })
        entries += "InhabitedTime" to TLong(random.nextLong(0, 100_000))
        entries += "structures" to compound("References" to compound(), "starts" to compound())
        entries += "ChunkBukkitValues" to compound("example:marker" to TString("value-$seed"))
        if (light) entries += "starlight.light_version" to TInt(10)
        return TCompound(entries)
    }

    private fun section(y: Int, style: PaletteStyle, random: Random, light: Boolean, seed: Int): TCompound {
        val entries = ArrayList<Pair<String, Tag>>()
        entries += "Y" to TByte(y)
        entries += "block_states" to blockStates(style, random)
        entries += "biomes" to compound("palette" to stringList(listOf("minecraft:plains")))
        if (light) {
            val lr = Random(seed * 131 + y)
            entries += "BlockLight" to TByteArray(ByteArray(2048) { (lr.nextInt(16) * 17).toByte() })
            entries += "starlight.blocklight_state" to TInt(2)
            entries += "SkyLight" to TByteArray(ByteArray(2048) { 0xFF.toByte() })
            entries += "starlight.skylight_state" to TInt(2)
        }
        return TCompound(entries)
    }

    private fun blockStates(style: PaletteStyle, random: Random): TCompound = when (style) {
        PaletteStyle.EMPTY -> {
            compound("palette" to compoundList(listOf(compound("Name" to TString("minecraft:air")))))
        }
        PaletteStyle.SINGLE -> {
            compound("palette" to compoundList(listOf(compound("Name" to TString("minecraft:stone")))))
        }
        PaletteStyle.FULL -> {
            val palette = (0 until 40).map { i ->
                val name = BLOCKS[i % BLOCKS.size]
                if (i < BLOCKS.size) {
                    compound("Name" to TString(name))
                } else {
                    compound("Name" to TString(name), "Properties" to compound("variant" to TString("v$i")))
                }
            }
            // 40 entries -> 6 bits, 10 entries per long, entries never span longs: 410 longs.
            val data = LongArray(410) { random.nextLong() and 0x0FFF_FFFF_FFFF_FFFFL }
            compound("palette" to compoundList(palette), "data" to TLongArray(data))
        }
    }

    private fun blockEntity(x: Int, z: Int, i: Int, random: Random): TCompound =
        if (i % 2 == 0) {
            compound(
                "id" to TString("minecraft:chest"),
                "x" to TInt(x * 16 + i), "y" to TInt(70), "z" to TInt(z * 16),
                "keepPacked" to TByte(0),
                "Items" to compoundList(
                    (0 until 3).map { slot ->
                        compound(
                            "Slot" to TByte(slot),
                            "id" to TString(BLOCKS[random.nextInt(BLOCKS.size)]),
                            "count" to TInt(random.nextInt(1, 65)),
                        )
                    },
                ),
            )
        } else {
            compound(
                "id" to TString("minecraft:piston"),
                "x" to TInt(x * 16 + i), "y" to TInt(71), "z" to TInt(z * 16 + 1),
                "keepPacked" to TByte(0),
                "blockState" to compound("Name" to TString("minecraft:sticky_piston")),
                "facing" to TInt(1), "progress" to TFloat(0.5f), "extending" to TByte(1), "source" to TByte(0),
            )
        }

    fun entities(x: Int, z: Int, count: Int, seed: Int): TCompound {
        val random = Random(seed)
        val list = (0 until count).map { i ->
            val passengers = if (i % 3 == 0) {
                listOf("Passengers" to compoundList(listOf(entity("minecraft:zombie", x, z, random, emptyList()))))
            } else {
                emptyList()
            }
            entity(if (i % 2 == 0) "minecraft:cow" else "minecraft:armor_stand", x, z, random, passengers)
        }
        return compound(
            "DataVersion" to TInt(DATA_VERSION),
            "Position" to TIntArray(intArrayOf(x, z)),
            "Entities" to compoundList(list),
        )
    }

    private fun entity(id: String, x: Int, z: Int, random: Random, extra: List<Pair<String, Tag>>): TCompound =
        TCompound(
            listOf(
                "id" to TString(id),
                "Pos" to list(6, listOf(TDouble(x * 16 + random.nextDouble(16.0)), TDouble(64.0), TDouble(z * 16 + 0.5))),
                "Rotation" to list(5, listOf(TFloat(random.nextFloat() * 360), TFloat(0f))),
                "UUID" to TIntArray(IntArray(4) { random.nextInt() }),
                "Health" to TFloat(10f),
            ) + extra,
        )

    fun poi(seed: Int): TCompound {
        val random = Random(seed)
        return compound(
            "DataVersion" to TInt(DATA_VERSION),
            "Sections" to compound(
                "4" to compound(
                    "Valid" to TByte(1),
                    "Records" to compoundList(
                        listOf(
                            compound(
                                "type" to TString("minecraft:home"),
                                "pos" to TIntArray(intArrayOf(random.nextInt(16), 64, random.nextInt(16))),
                                "free_tickets" to TInt(1),
                            ),
                        ),
                    ),
                ),
            ),
        )
    }
}
