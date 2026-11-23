import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spotless)
    alias(libs.plugins.licensee)
    alias(libs.plugins.cyclonedx.bom)
}

android {
    namespace = "com.akeyboard"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.max97k.akeyboard"
        minSdk = 31
        targetSdk = 37
        versionCode = 9
        versionName = "0.6.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                val props = Properties().apply {
                    keystorePropsFile.inputStream().use { load(it) }
                }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        // Intentional pinned BOM and dependency versions
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion"
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.autofill)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)

    constraints {
        implementation(libs.androidx.fragment) {
            because("play-app-update transitively brings fragment 1.1.0, which triggers InvalidFragmentVersionForActivityResult")
        }
    }

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        licenseHeaderFile(rootProject.file("spotless/copyright.kt"))
    }
}

licensee {
    allow("Apache-2.0")
    allow("MIT")
    allow("BSD-2-Clause")
    allow("BSD-3-Clause")
    allowUrl("https://developer.android.com/studio/terms.html") {
        because("Android SDK Terms of Service (Google Play Services transitive dependency)")
    }
    allowUrl("https://developer.android.com/guide/playcore/license") {
        because("Play Core SDK Terms of Service (Google Play In-App Updates)")
    }
    bundleAndroidAsset = true
}

tasks.named<org.cyclonedx.gradle.CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs = listOf("releaseRuntimeClasspath")
    projectType = org.cyclonedx.model.Component.Type.APPLICATION
    jsonOutput = layout.buildDirectory.file("reports/sbom.json")
    xmlOutput.unsetConvention()
}
