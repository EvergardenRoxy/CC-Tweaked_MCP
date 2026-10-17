// SPDX-License-Identifier: MPL-2.0

plugins {
    id("net.neoforged.moddev.legacyforge") version "2.0.148"
}

group = "cctrun"
version = "0.2.0-SNAPSHOT"

val mcVersion = "1.20.1"
val forgeBuild = "47.4.10"
// Same CC: Tweaked release as the engine (gradle/libs.versions.toml).
val ccTweakedVersion = "1.120.2"

base {
    archivesName = "cct-live-$mcVersion"
}

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
    compileOnly("cc.tweaked:cobalt:0.9.9")
}

tasks.processResources {
    val props = mapOf("version" to project.version, "cctVersion" to ccTweakedVersion)
    inputs.properties(props)
    filesMatching("META-INF/mods.toml") { expand(props) }
}
