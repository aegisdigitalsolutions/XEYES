plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM: the export package format must be verifiable without a device. Tests write packages to
// an in-memory sink and read them back with the same validator the Master uses.
dependencies {
    api(project(":core-model"))
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
