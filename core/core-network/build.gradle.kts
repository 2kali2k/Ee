plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api(project(":core:core-model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okio)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
