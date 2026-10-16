// SPDX-License-Identifier: MPL-2.0

plugins {
    `java-library`
}

dependencies {
    api(libs.cctweaked.core)
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testRuntimeOnly(libs.slf4j.nop)
}

tasks.test {
    useJUnitPlatform()
    // Several tests wait for real game time (timeouts, "Too long without yielding").
    maxParallelForks = 2
}
