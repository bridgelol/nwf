package gg.netherite.world.nwf.codec

import java.lang.reflect.Modifier
import java.nio.file.Path
import java.util.Properties
import java.util.jar.JarFile

/**
 * Checks that the zstd-jni this library links against has every member the codecs call ([ZstdSupport], the
 * dictionary trainer and [ZstdFramePipeline]). A host can put an older zstd-jni ahead of the one NWF was built
 * against (another component bundling its own copy, for example); without this check that surfaces as a
 * [NoSuchMethodError] in the middle of a decode. The check is reflection only: it neither initialises the zstd classes
 * nor loads the native library.
 */
object ZstdCapability {
    private const val PACKAGE = "com.github.luben.zstd."
    private const val POM = "META-INF/maven/com.github.luben/zstd-jni/pom.properties"
    private const val MANIFEST_SOURCE = "defining jar's manifest, not necessarily zstd-jni's"

    /** The size of the [roundTrip] buffer. */
    const val ROUND_TRIP_BYTES: Int = 64 * 1024
    private const val ROUND_TRIP_LEVEL = 3
    private const val ROUND_TRIP_PERIOD = 31

    private val REQUIRED_CLASSES = listOf("ZstdException")

    private val REQUIRED_MEMBERS: List<Member> = listOf(
        Member.static("Zstd", "getFrameContentSize", "long", "byte[]"),
        Member.static("Zstd", "findFrameCompressedSize", "long", "byte[]"),
        Member.static("Zstd", "isError", "boolean", "long"),
        Member.static("Zstd", "getErrorName", "java.lang.String", "long"),
        Member.static("Zstd", "minCompressionLevel", "int"),
        Member.static("Zstd", "maxCompressionLevel", "int"),
        Member.static("Zstd", "trainFromBuffer", "long", "byte[][]", "byte[]", "boolean"),
        Member.static("Zstd", "getDictIdFromDict", "long", "byte[]"),
        Member.constructor("ZstdCompressCtx"),
        Member.instance("ZstdCompressCtx", "setLevel", "ZstdCompressCtx", "int"),
        Member.instance("ZstdCompressCtx", "setChecksum", "ZstdCompressCtx", "boolean"),
        Member.instance("ZstdCompressCtx", "setContentSize", "ZstdCompressCtx", "boolean"),
        Member.instance("ZstdCompressCtx", "setDictID", "ZstdCompressCtx", "boolean"),
        Member.instance("ZstdCompressCtx", "loadDict", "ZstdCompressCtx", "ZstdDictCompress"),
        Member.instance("ZstdCompressCtx", "loadDict", "ZstdCompressCtx", "byte[]"),
        Member.instance("ZstdCompressCtx", "compress", "byte[]", "byte[]"),
        Member.instance("ZstdCompressCtx", "close", "void"),
        Member.constructor("ZstdDecompressCtx"),
        Member.instance("ZstdDecompressCtx", "loadDict", "ZstdDecompressCtx", "ZstdDictDecompress"),
        Member.instance("ZstdDecompressCtx", "loadDict", "ZstdDecompressCtx", "byte[]"),
        Member.instance(
            "ZstdDecompressCtx", "decompressByteArray", "int", "byte[]", "int", "int", "byte[]", "int", "int",
        ),
        Member.instance("ZstdDecompressCtx", "close", "void"),
        Member.constructor("ZstdDictCompress", "byte[]", "int"),
        Member.instance("ZstdDictCompress", "close", "void"),
        Member.constructor("ZstdDictDecompress", "byte[]"),
        Member.instance("ZstdDictDecompress", "close", "void"),
    )

    /**
     * What one check found: where zstd-jni came from, its version when known and where that version was read
     * ([versionSource]), and every member it lacks.
     */
    class Report(
        val source: String,
        val version: String?,
        val missing: List<String>,
        val versionSource: String? = null,
    ) {
        val ok: Boolean get() = missing.isEmpty()

        /** `zstd-jni <version> (<where the version was read>) from <jar>`. */
        val describe: String get() {
            val shown = version?.let { v -> versionSource?.let { "$v ($it)" } ?: v } ?: "(version unknown)"
            return "zstd-jni $shown from $source"
        }

        /** Null when every member is present; otherwise one sentence naming the jar, version and missing members. */
        val refusal: String? get() =
            if (ok) null else "$describe lacks ${missing.joinToString()}, which NWF calls"
    }

    /** The zstd-jni this class links against. Computed once per class loader (once per JVM in a single-app host). */
    val current: Report by lazy {
        val loader = ZstdCapability::class.java.classLoader
        inspect { name -> Class.forName(name, false, loader) }
    }

    /**
     * Checks the classes [resolve] returns (it throws [ClassNotFoundException] or a [LinkageError] for a missing one)
     * against what NWF calls. A seam: tests resolve stand-ins.
     */
    fun inspect(resolve: (String) -> Class<*>): Report {
        val missing = ArrayList<String>()
        // Every class first, each reported once; a member of a missing class is covered by that line.
        val resolved = LinkedHashMap<String, Class<*>?>()
        for (name in REQUIRED_CLASSES + REQUIRED_MEMBERS.map { it.owner }) {
            if (name in resolved) continue
            val found = resolveOrNull(resolve, PACKAGE + name)
            if (found == null) missing += "class $PACKAGE$name"
            resolved[name] = found
        }
        val zstdClass: (String) -> Class<*>? = { name ->
            resolved.getOrElse(name) { resolveOrNull(resolve, PACKAGE + name) }
        }
        for (member in REQUIRED_MEMBERS) {
            val ownerClass = resolved[member.owner] ?: continue
            if (!member.presentOn(ownerClass, zstdClass)) missing += member.display
        }
        val zstd = resolved["Zstd"]
        val version = zstd?.let { versionOf(it, resolve) }
        return Report(sourceOf(zstd), version?.first, missing, version?.second)
    }

    /**
     * Compresses and decompresses one buffer through [ZstdSupport] (this loads the native library) and returns the
     * compressed size. Throws when the bytes do not come back unchanged; any zstd or linkage failure propagates.
     */
    fun roundTrip(): Int {
        val src = ByteArray(ROUND_TRIP_BYTES) { (it % ROUND_TRIP_PERIOD).toByte() }
        val packed = ZstdSupport.compress(src, ROUND_TRIP_LEVEL)
        val back = ZstdSupport.decompress(packed, src.size, "zstd round trip")
        check(back.contentEquals(src)) { "zstd round trip changed the bytes" }
        return packed.size
    }

    /** The jar (or directory) [cls] was defined from, else its class loader. */
    fun sourceOf(cls: Class<*>?): String {
        if (cls == null) return "(not resolvable)"
        return locationOf(cls) ?: "class loader ${cls.classLoader ?: "bootstrap"}"
    }

    @Suppress("SwallowedException")
    private fun resolveOrNull(resolve: (String) -> Class<*>, name: String): Class<*>? = try {
        resolve(name)
    } catch (_: ClassNotFoundException) {
        null
    } catch (_: LinkageError) {
        null
    }

    /**
     * zstd-jni's version and where it was read, from the jar that defined [zstd] only: zstd-jni's own
     * `util.ZstdVersion.VERSION` (absent from 1.4.8) when that class comes from the same jar, else `version` in
     * zstd-jni's Maven metadata in that jar, and only when both are absent the manifest `Implementation-Version`. The
     * manifest is the defining jar's, so in a jar that bundles zstd-jni it is that jar's version; the label says so.
     */
    private fun versionOf(zstd: Class<*>, resolve: (String) -> Class<*>): Pair<String, String>? =
        constantVersion(zstd, resolve)?.let { it to "ZstdVersion" }
            ?: pomVersion(zstd)?.let { it to "pom.properties" }
            ?: zstd.`package`?.implementationVersion?.let { it to MANIFEST_SOURCE }

    /** `ZstdVersion.VERSION`, when [resolve] finds that class in [zstd]'s own jar (another jar's would mislead). */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun constantVersion(zstd: Class<*>, resolve: (String) -> Class<*>): String? = try {
        val version = resolveOrNull(resolve, PACKAGE + "util.ZstdVersion")
        if (version == null || locationOf(version) == null || locationOf(version) != locationOf(zstd)) {
            null
        } else {
            version.getField("VERSION").get(null) as? String
        }
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun locationOf(cls: Class<*>): String? =
        runCatching { cls.protectionDomain?.codeSource?.location?.toString() }.getOrNull()

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun pomVersion(cls: Class<*>): String? = try {
        val location = cls.protectionDomain?.codeSource?.location
        val file = location?.takeIf { it.protocol == "file" }?.let { Path.of(it.toURI()).toFile() }
        file?.takeIf { it.isFile }?.let(::readPomVersion)
    } catch (_: Exception) {
        null
    }

    private fun readPomVersion(file: java.io.File): String? = JarFile(file).use { jar ->
        val entry = jar.getJarEntry(POM) ?: return@use null
        jar.getInputStream(entry).use { input -> Properties().apply { load(input) }.getProperty("version") }
    }

    /**
     * One member NWF calls, with the exact descriptor it links against. Types are JVM primitives, `byte[]`,
     * `byte[][]`, `java.lang.String`, or the simple name of a zstd-jni class.
     */
    private class Member(
        val owner: String,
        val name: String?,
        val returns: String?,
        val params: List<String>,
        val static: Boolean,
    ) {
        val display: String = when (name) {
            null -> "$owner(${params.joinToString()})"
            else -> "$owner.$name(${params.joinToString()})"
        }

        @Suppress("SwallowedException", "ReturnCount")
        fun presentOn(ownerClass: Class<*>, zstdClass: (String) -> Class<*>?): Boolean {
            val types = params.map { typeOf(it, zstdClass) ?: return false }.toTypedArray()
            return try {
                if (name == null) {
                    Modifier.isPublic(ownerClass.getConstructor(*types).modifiers)
                } else {
                    val method = ownerClass.getMethod(name, *types)
                    Modifier.isStatic(method.modifiers) == static && method.returnType == typeOf(returns!!, zstdClass)
                }
            } catch (_: NoSuchMethodException) {
                false
            } catch (_: LinkageError) {
                false
            }
        }

        companion object {
            fun static(owner: String, name: String, returns: String, vararg params: String) =
                Member(owner, name, returns, params.toList(), static = true)

            fun instance(owner: String, name: String, returns: String, vararg params: String) =
                Member(owner, name, returns, params.toList(), static = false)

            fun constructor(owner: String, vararg params: String) =
                Member(owner, null, null, params.toList(), static = false)

            private fun typeOf(name: String, zstdClass: (String) -> Class<*>?): Class<*>? = when (name) {
                "void" -> Void.TYPE
                "int" -> Integer.TYPE
                "long" -> java.lang.Long.TYPE
                "boolean" -> java.lang.Boolean.TYPE
                "byte[]" -> ByteArray::class.java
                "byte[][]" -> Array<ByteArray>::class.java
                "java.lang.String" -> String::class.java
                else -> zstdClass(name)
            }
        }
    }
}
