plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Deliberately a pure-JVM module. `core-model` is the cross-component contract, so it must not
// depend on Android: that is what lets the correctness-critical logic (validation, codecs,
// normalization) run in `./gradlew test` with no SDK, emulator or device.
dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
    testImplementation(libs.kotlin.test)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

tasks.test {
    useJUnitPlatform()
}
