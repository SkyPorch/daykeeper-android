plugins {
    id("com.android.library")
    `maven-publish`
}

android {
    namespace = "com.skyporch.daykeeper.ui"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}

kotlin {
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    api(project(":daykeeper"))
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "daykeeper-android-ui"
                pom {
                    name.set("Daykeeper Android Messenger")
                    description.set("Native Android customer messenger for Daykeeper")
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
