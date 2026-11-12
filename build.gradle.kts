// Root build.gradle.kts for Manga Reader
// applicationId = "com.aistudio.mangareader.qrxvwt"
val applicationId by extra("com.aistudio.mangareader.qrxvwt")

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
