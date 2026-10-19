// SPDX-License-Identifier: MPL-2.0

plugins {
    `java-library`
}

dependencies {
    api(libs.cctweaked.core)
    implementation(libs.gson)
    // The pinned VM adapter observes Cobalt's original Java error before conversion to Lua text.
    compileOnly(libs.cobalt)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    systemProperty("cctrun.build.version", project.version.toString())
    useJUnitPlatform()
    // Several tests wait for real game time (timeouts, "Too long without yielding").
    maxParallelForks = 2
}
