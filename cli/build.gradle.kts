plugins {
    application
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.gson)
    runtimeOnly(libs.slf4j.nop)
}

application {
    applicationName = "cct-run"
    mainClass = "cctrun.cli.Main"
    // Startup flags that cut JVM start time for short runs (see handoff.md, JVM_COST).
    applicationDefaultJvmArgs = listOf("-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC", "-Xss4m")
}
