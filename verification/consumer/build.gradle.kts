import java.security.MessageDigest

plugins {
    id("com.android.library") version "9.2.1"
}

android {
    namespace = "com.skyporch.daykeeper.verification"
    compileSdk = 37
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    implementation("io.github.skyporch:daykeeper-android:0.1.0-SNAPSHOT")
    implementation("io.github.skyporch:daykeeper-android-ui:0.1.0-SNAPSHOT")
    testImplementation("junit:junit:4.13.2")
}

tasks.register("verifyDaykeeperArtifacts") {
    val classpath = configurations.getByName("debugRuntimeClasspath")
    doLast {
        val expected =
            mapOf(
                "daykeeper-android" to
                    providers.gradleProperty("daykeeperExpectedCoreSha256").get(),
                "daykeeper-android-ui" to
                    providers.gradleProperty("daykeeperExpectedUiSha256").get(),
            )
        val found = mutableSetOf<String>()
        classpath.incoming.artifacts.artifacts.forEach { artifact ->
            val component =
                artifact.id.componentIdentifier
                    as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
            if (component?.group == "io.github.skyporch") {
                check(artifact.file.extension == "aar") { "Expected packaged AAR" }
                val digest =
                    MessageDigest.getInstance("SHA-256")
                        .digest(artifact.file.readBytes())
                        .joinToString("") { "%02x".format(it) }
                check(digest == expected[component.module]) {
                    "Resolved artifact does not match this build: ${component.module}"
                }
                found.add(component.module)
                println("Verified ${component.module}:${component.version} sha256=$digest")
            }
        }
        check(found == expected.keys) { "Both Daykeeper Maven artifacts must resolve" }
    }
}
