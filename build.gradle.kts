// SPDX-License-Identifier: MPL-2.0

val toolVersions = Regex("public static final String TOOL = \"([^\"]+)\"")
    .findAll(file("engine/src/main/java/cctrun/engine/Versions.java").readText()).toList()
require(toolVersions.size == 1) { "Expected exactly one Versions.TOOL definition" }
val toolVersion = toolVersions.single().groupValues[1]

allprojects {
    group = "cctrun"
    version = toolVersion
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
    from(project(":mod-forge").layout.buildDirectory.file("libs/cct-live-1.20.1-$toolVersion.jar"))
    from(project(":mod-neoforge").layout.buildDirectory.file("libs/cct-live-1.21.1-$toolVersion.jar"))
    into(layout.buildDirectory.dir("mods"))
}

tasks.register("printVersion") { doLast { println(toolVersion) } }
tasks.register("verifyReleaseVersion") {
    doLast {
        val tag = providers.environmentVariable("RELEASE_TAG").orElse(providers.environmentVariable("GITHUB_REF_NAME")).orNull
        require(tag == "v$toolVersion") { "Release tag '$tag' does not match build/runtime version v$toolVersion" }
    }
}
