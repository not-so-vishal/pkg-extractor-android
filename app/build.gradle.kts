plugins {
    id("com.android.application")
}
android {
    namespace = "com.example.pkgextractor"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.pkgextractor"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
// No third-party dependencies on purpose: works with Code On the Go's offline Maven repo.
