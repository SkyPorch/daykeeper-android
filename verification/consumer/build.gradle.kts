plugins {
    id("com.android.library") version "8.13.0"
    kotlin("android") version "2.2.0"
}

android {
    namespace = "com.skyporch.daykeeper.verification"
    compileSdk = 36
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
