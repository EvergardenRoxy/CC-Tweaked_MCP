// SPDX-License-Identifier: MPL-2.0

// Forge 1.20.1 variant. Shared sources live in mod/src/common; this project only holds the loader shell
// (CctLive, LiveConfig) and the Forge mod metadata.
plugins {
    id("net.neoforged.moddev.legacyforge") version "2.0.148"
}

val mcVersion = "1.20.1"
val forgeBuild = "47.4.10"
// Same CC: Tweaked release as the engine (gradle/libs.versions.toml).
val ccTweakedVersion = libs.versions.cctweaked.get()

base {
    archivesName = "cct-live-$mcVersion"
}

sourceSets {
    main {
        java.srcDir(rootProject.file("mod/src/common/java"))
        resources.srcDir(rootProject.file("mod/src/common/resources"))
    }
    test {
        java.srcDir(rootProject.file("mod/src/common/test/java"))
    }
}

java { toolchain.languageVersion = JavaLanguageVersion.of(21) }

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
}

repositories {
    exclusiveContent {
        forRepository { maven("https://maven.squiddev.cc") }
        filter { includeGroup("cc.tweaked") }
    }
}

legacyForge {
    enable {
        forgeVersion = "$mcVersion-$forgeBuild"
    }

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
    // The Forge jar contains CC: Tweaked's core as well (Cobalt is nested via jar-in-jar).
    modImplementation("cc.tweaked:cc-tweaked-$mcVersion-forge:$ccTweakedVersion")

    // Plain unit tests for code that does not touch Minecraft (e.g. ComputerPaths, SnapshotManifest).
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.gson)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version, "cctVersion" to ccTweakedVersion)
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") { expand(props) }
}
