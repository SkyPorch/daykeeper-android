import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.android.library")
    id("com.vanniktech.maven.publish")
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
}

kotlin {
    jvmToolchain(17)
    compilerOptions { allWarningsAsErrors.set(true) }
}

dependencies {
    api(project(":daykeeper"))
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}

mavenPublishing {
    configure(
        AndroidSingleVariantLibrary(
            javadocJar = JavadocJar.Empty(),
            sourcesJar = SourcesJar.Sources(),
            variant = "release",
        ),
    )
    coordinates(group.toString(), "daykeeper-android-ui", version.toString())
    pom {
        name.set("Daykeeper Android Messenger")
        description.set("Native Android customer messenger for Daykeeper")
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
