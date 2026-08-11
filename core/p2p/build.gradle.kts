plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.iptv.tv.core.p2p"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.eddsa)

    implementation(libs.libtorrent4j)
    runtimeOnly(libs.libtorrent4j.android.arm)
    runtimeOnly(libs.libtorrent4j.android.arm64)
    runtimeOnly(libs.libtorrent4j.android.x8664)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
