// SPDX-License-Identifier: MPL-2.0

plugins {
    application
}

// The launcher (cctrun.cli.Launcher, JavaFinder) is compiled for Java 8 so it runs on any java the start scripts
// find and can switch to an installed Java 17+ (handoff.md N3/D27). Everything else needs Java 17.
val launcher: SourceSet = sourceSets.create("launcher")

tasks.named<JavaCompile>(launcher.compileJavaTaskName) {
    options.release = 8
    options.compilerArgs.add("-Xlint:-options") // "source value 8 is obsolete" on newer JDKs
}

tasks.jar {
    from(launcher.output)
}

// `cct-run guide` prints docs/agent-guide.md from inside the jar.
tasks.processResources {
    from(rootProject.layout.projectDirectory.file("docs/agent-guide.md")) { into("cctrun") }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.gson)
    runtimeOnly(libs.slf4j.nop)

    testImplementation(launcher.output)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

application {
    applicationName = "cct-run"
    mainClass = "cctrun.cli.Launcher"
    // Startup flags that cut JVM start time for short runs (see handoff.md, JVM_COST).
    applicationDefaultJvmArgs = listOf("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xss4m")
}

// The release zip carries the licences of everything inside it, plus what testers need.
distributions {
    main {
        contents {
            from(rootProject.layout.projectDirectory) {
                include("LICENSE", "LICENSES/**", "THIRD-PARTY-NOTICES.md", "examples/**", "scripts/**", "docs/testing.md", "docs/agent-guide.md")
            }
            filesMatching("scripts/*.sh") {
                permissions { unix("rwxr-xr-x") }
            }
        }
    }
}
