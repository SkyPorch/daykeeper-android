plugins {
    id("com.android.library") version "9.2.1" apply false
    id("com.android.application") version "9.2.1" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    kotlin("plugin.serialization") version "2.2.10" apply false
}

val daykeeperVersion =
    providers.gradleProperty("daykeeperVersion").orElse("0.1.0-SNAPSHOT").get()
val semverIdentifier = "(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
require(
    daykeeperVersion.matches(
        Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                "(?:-${semverIdentifier}(?:\\.${semverIdentifier})*)?$",
        ),
    ),
) { "daykeeperVersion must be an exact SemVer value" }

allprojects {
    group = "io.github.skyporch"
    version = daykeeperVersion
    tasks.withType<Jar>().configureEach {
        if (name == "sourceReleaseJar") {
            from(rootProject.file("LICENSE")) { into("META-INF") }
        }
    }
}
