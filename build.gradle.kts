plugins {
    kotlin("jvm") version "2.4.21"
    `java-library`
    `maven-publish`
}

group = "gg.netherite"
version = file("VERSION").readText().trim()

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        javaParameters.set(true)
    }
}

java {
    withSourcesJar()
}

dependencies {
    api("com.github.luben:zstd-jni:1.5.7-24")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "nwf"
            from(components["java"])
            pom {
                name.set("NWF")
                description.set("A chunk-granular Minecraft world format: reader, writer and tools.")
                url.set("https://github.com/bridgelol/nwf")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                scm {
                    url.set("https://github.com/bridgelol/nwf")
                    connection.set("scm:git:https://github.com/bridgelol/nwf.git")
                }
            }
        }
    }
}
