package gg.netherite.world.nwf.codec

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ZstdCapabilityTest {
    private val zstd = "com.github.luben.zstd.Zstd"

    @Test
    fun `the zstd-jni NWF builds against has every member it calls`() {
        val report = ZstdCapability.inspect { Class.forName(it) }
        assertEquals(emptyList<String>(), report.missing)
        assertTrue(report.ok)
        assertNull(report.refusal)
        assertTrue(ZstdCapability.current.ok, ZstdCapability.current.describe)
    }

    @Test
    fun `the version comes from zstd-jni itself, not the jar's manifest`() {
        val report = ZstdCapability.inspect { Class.forName(it) }
        assertEquals(com.github.luben.zstd.util.ZstdVersion.VERSION, report.version)
        assertEquals("ZstdVersion", report.versionSource)
        assertTrue(report.describe.startsWith("zstd-jni ${report.version} (ZstdVersion) from "), report.describe)
    }

    @Test
    fun `a ZstdVersion from another jar than Zstd is not reported as its version`() {
        // The stand-in Zstd comes from the test classes, the real ZstdVersion from the zstd-jni jar: a shaded jar
        // without ZstdVersion would otherwise borrow the version of whichever jar the lookup finds next.
        val report = ZstdCapability.inspect { name -> if (name == zstd) OldZstd::class.java else Class.forName(name) }
        assertNull(report.version, report.describe)
        assertTrue(report.describe.startsWith("zstd-jni (version unknown) from "), report.describe)
    }

    @Test
    fun `an older Zstd without the frame-size methods is refused, naming each one`() {
        val report = ZstdCapability.inspect { name -> if (name == zstd) OldZstd::class.java else Class.forName(name) }
        // getFrameContentSize exists on the stand-in but returns int: a different descriptor, so it does not link.
        assertEquals(listOf("Zstd.getFrameContentSize(byte[])", "Zstd.findFrameCompressedSize(byte[])"), report.missing)
        val refusal = checkNotNull(report.refusal)
        assertTrue(refusal.contains("Zstd.findFrameCompressedSize(byte[])"), refusal)
        assertTrue(refusal.startsWith("zstd-jni "), refusal)
    }

    @Test
    fun `a missing class is reported once, with the members of other classes that take it`() {
        val gone = "com.github.luben.zstd.ZstdDictDecompress"
        val report = ZstdCapability.inspect { name ->
            if (name == gone) throw ClassNotFoundException(name) else Class.forName(name)
        }
        assertEquals(
            listOf(
                "class $gone",
                "ZstdDecompressCtx.loadDict(ZstdDictDecompress)",
            ),
            report.missing,
        )
    }

    @Test
    fun `an unresolvable Zstd reports no source jar`() {
        val report = ZstdCapability.inspect { name -> throw ClassNotFoundException(name) }
        assertEquals("(not resolvable)", report.source)
        assertTrue(report.missing.contains("class $zstd"))
    }

    @Test
    fun `the round trip compresses and restores the bytes`() {
        val packed = ZstdCapability.roundTrip()
        assertTrue(packed in 1 until 64 * 1024, "compressed size $packed")
    }

    /** zstd-jni 1.4.8's static surface as NWF sees it, with getFrameContentSize at a different return type. */
    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant")
    object OldZstd {
        @JvmStatic fun getFrameContentSize(src: ByteArray): Int = 0

        @JvmStatic fun isError(code: Long): Boolean = false

        @JvmStatic fun getErrorName(code: Long): String = ""

        @JvmStatic fun minCompressionLevel(): Int = 1

        @JvmStatic fun maxCompressionLevel(): Int = 1

        @JvmStatic fun trainFromBuffer(samples: Array<ByteArray>, dict: ByteArray, legacy: Boolean): Long = 0

        @JvmStatic fun getDictIdFromDict(dict: ByteArray): Long = 0
    }
}
