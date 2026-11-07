// Standalone build of the NWF library.
plugins {
    // Provisions the JDK toolchain when the machine running Gradle has none (CI, JitPack).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "nwf"
