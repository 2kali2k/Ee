// Root build file. All plugin application happens in the module build files;
// here we only declare the plugins (and their versions, via the version catalog)
// so that the build is evaluated once.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
