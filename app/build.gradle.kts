import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.catatanku.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.catatanku.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.7"
        // Hanya arm64: hampir semua HP Android modern. Hapus baris ini bila perlu emulator x86_64.
        ndk { abiFilters += "arm64-v8a" }
    }
    // Kunci tanda tangan tetap dibaca dari keystore.properties (tidak ikut repositori). Tanpa berkas itu,
    // build rilis memakai kunci debug mesin ini, yang TIDAK bisa memperbarui APK yang dibangun di mesin lain.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        // Build uji bisa dipasang berdampingan dengan rilis resmi, tanpa menyentuh datanya.
        debug { applicationIdSuffix = ".uji"; versionNameSuffix = "-uji" }
        release {
            // R8 membuang kode pustaka yang tidak dipakai (sebagian besar isi APK selain mesin AI native) dan
            // menyusutkan sumber daya. Nama kelas tidak disamarkan (lihat proguard-rules.pro): aplikasi ini sumber
            // terbuka, dan jejak galat yang terbaca lebih berguna daripada beberapa ratus KB.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Tanpa hash commit di dalam APK, sehingga build bisa direproduksi persis (syarat F-Droid
            // memakai tanda tangan pengembang): APK GitHub dan APK buatan F-Droid identik.
            vcsInfo.include = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    // Aplikasi ini berbahasa Indonesia; terjemahan pustaka ke puluhan bahasa lain tidak ikut dibawa.
    androidResources { localeFilters += listOf("id", "en") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true      // diperlukan uji tangkapan layar (Robolectric)
            all { t ->
                t.maxHeapSize = "2g"
                t.setForkEvery(1)                 // tiap kelas uji di proses sendiri: data tidak bocor antar-uji
                t.systemProperty("robolectric.graphicsMode", "NATIVE")
                // Opsional: -Pshots=true merekam gambar ke app/build/shots; -ProboRepo=<url> memakai mirror Maven.
                t.systemProperty("roborazzi.test.record", (project.findProperty("shots") ?: "false").toString())
                project.findProperty("roboRepo")?.let { t.systemProperty("robolectric.dependency.repo.url", it.toString()) }
                // Opsional: -Pperf=true menjalankan pengukuran mesin pencarian (PerfTest); hasilnya di laporan uji.
                t.systemProperty("perf", (project.findProperty("perf") ?: "false").toString())
            }
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // LLM lokal (pengganti resmi MediaPipe LLM Inference)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.1")

    testImplementation("junit:junit:4.13.2")
    // Render layar Compose ke PNG di JVM, tanpa perangkat
    testImplementation("org.robolectric:robolectric:4.18")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.79.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.79.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
