plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.ee"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ee"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0-m2"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(project(":design-system"))
    implementation(project(":feature:feature-home"))
    implementation(project(":feature:feature-filemanager"))
    implementation(project(":feature:feature-settings"))
    implementation(project(":feature:feature-video"))
    implementation(project(":feature:feature-audio"))
    implementation(project(":feature:feature-imageviewer"))
    implementation(project(":feature:feature-transfers"))
    implementation(project(":feature:feature-network"))
    implementation(project(":core:core-file"))
    implementation(project(":core:core-model"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-database"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-security"))
    implementation(project(":providers:provider-local"))
    implementation(project(":providers:provider-archive"))
    implementation(project(":providers:provider-media"))
    implementation(project(":providers:provider-smb"))
    implementation(project(":providers:provider-sftp"))
    implementation(project(":providers:provider-ftp"))
    implementation(project(":providers:provider-webdav"))
    implementation(project(":providers:provider-http"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.tooling.preview)
}
