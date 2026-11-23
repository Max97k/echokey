plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.licensee) apply false
    alias(libs.plugins.cyclonedx.bom) apply false
}
