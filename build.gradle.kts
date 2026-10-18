// SPDX-License-Identifier: MPL-2.0

allprojects {
    group = "cctrun"
    version = "0.2.3"
}

// Shared configuration for the loader-independent subprojects (engine, cli).
subprojects {
    // The mod variants configure the Java plugin, toolchain and repositories themselves (see mod/*/build.gradle.kts).
    if (name.startsWith("mod-")) return@subprojects

    repositories {
        mavenCentral()

        // CC: Tweaked publishes its Minecraft-independent core (and Cobalt) here.
        exclusiveContent {
            forRepository { maven("https://maven.squiddev.cc") }
            filter { includeGroup("cc.tweaked") }
        }
    }

    apply(plugin = "java")

    tasks.withType<JavaCompile>().configureEach {
        // Minecraft 1.20.1 ships a Java 17 runtime, so that is what players are guaranteed to have.
        options.release = 17
        options.encoding = "UTF-8"
    }
}

// One invocation builds every mod variant: build/mods/cct-live-1.20.1-<version>.jar and cct-live-1.21.1-<version>.jar.
tasks.register<Copy>("assembleMods") {
    dependsOn(":mod-forge:build", ":mod-neoforge:build")
    from(project(":mod-forge").layout.buildDirectory.dir("libs")) {
        include("*.jar")
    }
    from(project(":mod-neoforge").layout.buildDirectory.dir("libs")) {
        include("*.jar")
    }
    into(layout.buildDirectory.dir("mods"))
}
