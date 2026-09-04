import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
    kotlin("plugin.serialization")
}

android {
    namespace = "com.skyporch.daykeeper"
    compileSdk = 36
    defaultConfig { minSdk = 23 }
    buildFeatures { buildConfig = true }
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
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.4.0")
}

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )
    coordinates(group.toString(), "daykeeper-android", version.toString())
    pom {
        name.set("Daykeeper Android")
        description.set("Tenant-bound customer support SDK for Android")
        inceptionYear.set("2026")
        url.set("https://github.com/SkyPorch/daykeeper-android")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit/")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("skyporch")
                name.set("SkyPorch")
                url.set("https://github.com/SkyPorch")
            }
        }
        scm {
            url.set("https://github.com/SkyPorch/daykeeper-android")
            connection.set("scm:git:https://github.com/SkyPorch/daykeeper-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/SkyPorch/daykeeper-android.git")
        }
    }
    if (providers.gradleProperty("daykeeperCentralRelease").orNull == "true") {
        publishToMavenCentral(automaticRelease = false)
        signAllPublications()
    }
}

afterEvaluate {
    publishing {
        repositories {
            maven {
                name = "verification"
                url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI()
            }
        }
    }
}
