// SPDX-License-Identifier: MPL-2.0

// Standalone build: the mod needs a Minecraft toolchain (several hundred MB of downloads), so it is kept out
// of the root build. Run it with the root wrapper: ./gradlew -p mod build
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven.neoforged.net/releases")
    }
}

plugins {
    // Minecraft 1.20.1 tooling needs a Java 17 toolchain; download one if none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "cct-live"
