// Shared configuration for all subprojects.
subprojects {
    apply(plugin = "java")

    group = "cctrun"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()

        // CC: Tweaked publishes its Minecraft-independent core (and Cobalt) here.
        exclusiveContent {
            forRepository { maven("https://maven.squiddev.cc") }
            filter { includeGroup("cc.tweaked") }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        // Minecraft 1.20.1 ships a Java 17 runtime, so that is what players are guaranteed to have.
        options.release = 17
        options.encoding = "UTF-8"
    }
}
