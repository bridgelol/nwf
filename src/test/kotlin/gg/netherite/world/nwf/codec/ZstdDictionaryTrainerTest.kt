package gg.netherite.world.nwf.codec

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ZstdDictionaryTrainerTest {
    @Test
    fun `trained dictionaries respect the size bound and carry an id`() {
        val dict = TestDictionaries.chunk
        assertTrue(dict.size in ZstdDictionaryTrainer.MIN_DICTIONARY_BYTES..16 * 1024)
        assertNotEquals(0, dict.zstdDictId)
        assertTrue(dict.verified())
        assertNotEquals(TestDictionaries.chunk.zstdDictId, TestDictionaries.other.zstdDictId)
        assertArrayEquals(dict.bytes(), NwfDictionaries.of(dict.bytes()).bytes())
    }

    @Test
    fun `bad inputs are refused`() {
        assertThrows<IllegalArgumentException> { ZstdDictionaryTrainer.train(emptyList(), 4096) }
        assertThrows<IllegalArgumentException> { ZstdDictionaryTrainer.train(listOf(ByteArray(10)), 16) }
        assertThrows<IllegalArgumentException> { ZstdDictionaryTrainer.train(listOf(byteArrayOf(1, 2, 3)), 4096) }
        assertThrows<IllegalArgumentException> { NwfDictionaries.of(ByteArray(64) { 1 }) }
    }

    @Test
    fun `training is deterministic`() {
        val samples = (0 until 200).map { i ->
            nbtBytes(TestChunks.chunk(i, i, PaletteStyle.FULL, 70_000 + i, sections = 3, blockEntities = i % 3))
        }
        val first = ZstdDictionaryTrainer.train(samples, 8 * 1024)
        val second = ZstdDictionaryTrainer.train(samples.map { it.copyOf() }, 8 * 1024)
        assertArrayEquals(first, second)
        val a = NwfDictionaries.of(first)
        val b = NwfDictionaries.of(second)
        assertEquals(a.sha256, b.sha256)
        assertEquals(a.zstdDictId, b.zstdDictId)
    }
}
