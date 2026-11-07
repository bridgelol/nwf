package gg.netherite.world.nwf.codec

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdException
import gg.netherite.world.nwf.DictionaryTrainer
import gg.netherite.world.nwf.NwfDictionary

/**
 * Trains zstd dictionaries with zstd's default trainer. Pure CPU. Samples are uncompressed, already filtered
 * frames of one content class and kind; zstd needs a sample set several times larger than the dictionary.
 */
object ZstdDictionaryTrainer : DictionaryTrainer {
    /** The smallest dictionary zstd's trainer accepts. */
    const val MIN_DICTIONARY_BYTES: Int = 256

    /** The suggested dictionary size, 112 KiB. */
    const val DEFAULT_DICTIONARY_BYTES: Int = 112 * 1024

    /** Throws [IllegalArgumentException] when the samples cannot train a dictionary (too few or too uniform). */
    override fun train(samples: List<ByteArray>, sizeBytes: Int): ByteArray {
        require(sizeBytes >= MIN_DICTIONARY_BYTES) { "dictionary size $sizeBytes below $MIN_DICTIONARY_BYTES" }
        require(samples.isNotEmpty()) { "no samples" }
        val buffer = ByteArray(sizeBytes)
        val result = try {
            Zstd.trainFromBuffer(samples.toTypedArray(), buffer, false)
        } catch (e: ZstdException) {
            throw IllegalArgumentException("zstd dictionary training failed: ${e.message}", e)
        }
        require(!Zstd.isError(result)) { "zstd dictionary training failed: ${Zstd.getErrorName(result)}" }
        require(result in 1..sizeBytes) { "zstd dictionary training returned $result bytes" }
        return buffer.copyOf(result.toInt())
    }
}

/** Builds [NwfDictionary] values from dictionary bytes. */
object NwfDictionaries {
    /**
     * Wraps trained (or fetched) dictionary bytes: computes the SHA-256 (the dictionary's object id) and reads the zstd
     * dictionary id from the bytes. Throws [IllegalArgumentException] for bytes that are not a zstd dictionary.
     */
    fun of(bytes: ByteArray): NwfDictionary {
        val id = Zstd.getDictIdFromDict(bytes)
        require(id != 0L) { "not a zstd dictionary (no dictionary id)" }
        return NwfDictionary(sha256(bytes), id.toInt(), bytes)
    }
}
