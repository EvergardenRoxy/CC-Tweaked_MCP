// SPDX-License-Identifier: MPL-2.0

plugins {
    id("net.neoforged.moddev") version "2.0.148"
}

group = "cctrun"
version = "0.2.3"

val mcVersion = "1.21.1"
// CC: Tweaked publishes its NeoForge build under the "forge" artifact name for 1.21.1.
val neoForgeVersion = "21.1.262"
val ccTweakedVersion = "1.120.2"

base {
    archivesName = "cct-live-1.21.1"
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
}

repositories {
    exclusiveContent {
        forRepository { maven("https://maven.squiddev.cc") }
        filter { includeGroup("cc.tweaked") }
    }
}

neoForge {
    version = neoForgeVersion

    runs {
        create("client") {
            client()
        }
        create("server") {
            server()
            programArgument("--nogui")
        }
    }

    mods {
        create("cctlive") {
            sourceSet(sourceSets.main.get())
        }
    }
}

dependencies {
    // The CC: Tweaked 1.21.1 "forge" artifact is a NeoForge mod (META-INF/neoforge.mods.toml,
    // requires neoforge [21.1.9,21.2)); it contains the core API and the shared classes this mod uses.
    implementation("cc.tweaked:cc-tweaked-1.21.1-forge:1.120.2")

    // Plain unit tests for code that does not touch Minecraft (e.g. ComputerPaths, SnapshotManifest).
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version, "cctVersion" to ccTweakedVersion)
    inputs.properties(props)
    filesMatching("META-INF/neoforge.mods.toml") { expand(props) }
}
