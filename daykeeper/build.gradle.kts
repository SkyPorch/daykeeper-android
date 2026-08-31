plugins {
    id("com.android.library")
    kotlin("android")
    kotlin("plugin.serialization")
    `maven-publish`
}

android {
    namespace = "com.skyporch.daykeeper"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin {
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "daykeeper-android"
                pom {
                    name.set("Daykeeper Android")
                    description.set("Tenant-bound customer support SDK for Android")
                    url.set("https://github.com/SkyPorch/daykeeper-android")
                    licenses {
                        license {
                            name.set("MIT")
                            url.set("https://opensource.org/license/mit/")
                        }
                    }
                    developers {
                        developer {
                            id.set("skyporch")
                            name.set("SkyPorch")
                        }
                    }
                    scm {
                        url.set("https://github.com/SkyPorch/daykeeper-android")
                        connection.set("scm:git:https://github.com/SkyPorch/daykeeper-android.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "verification"
                url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI()
            }
        }
    }
}
