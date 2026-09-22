import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val keystorePropertiesFile = rootProject.projectDir.parentFile.resolve("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use(::load)
    }
}

android {
    namespace = "jp.local.recipemanager"
    compileSdk = 37

    defaultConfig {
        applicationId = "jp.local.recipemanager"
        minSdk = 26
        targetSdk = 37
        versionCode = 100
        versionName = "1.00"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropertiesFile.isFile) {
            create("release") {
                storeFile = file(requireNotNull(keystoreProperties.getProperty("storeFile")) { "storeFile is required" })
                storePassword = requireNotNull(keystoreProperties.getProperty("storePassword")) { "storePassword is required" }
                keyAlias = requireNotNull(keystoreProperties.getProperty("keyAlias")) { "keyAlias is required" }
                keyPassword = requireNotNull(keystoreProperties.getProperty("keyPassword")) { "keyPassword is required" }
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (keystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets["main"].assets.directories.add(rootProject.projectDir.parentFile.resolve("document/schemas").path)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.1")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("com.networknt:json-schema-validator:2.0.4") {
        exclude(group = "com.fasterxml.jackson.dataformat", module = "jackson-dataformat-yaml")
    }
    ksp("androidx.room:room-compiler:2.8.5")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.room:room-testing:2.8.5")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

tasks.register<Copy>("copyDebugApkToDst") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.projectDir.parentFile.resolve("dst"))
    rename { "recipe-manager-debug.apk" }
}

tasks.register<Copy>("copyReleaseApkToDst") {
    dependsOn("assembleRelease")
    doFirst {
        check(keystorePropertiesFile.isFile) {
            "keystore.properties is required to create a distributable Release APK."
        }
    }
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.projectDir.parentFile.resolve("dst"))
    rename { "recipe-manager-release.apk" }
}
