plugins {
    id("com.android.library") version "9.2.1" apply false
    id("com.android.application") version "9.2.1" apply false
    kotlin("plugin.serialization") version "2.2.10" apply false
}

allprojects {
    group = "io.github.skyporch"
    version = "0.1.0-SNAPSHOT"
    tasks.withType<Jar>().configureEach {
        if (name == "sourceReleaseJar") {
            from(rootProject.file("LICENSE")) { into("META-INF") }
        }
    }
}
