plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM. Built alongside core-export in Milestone 1 on purpose: an exporter whose packages have
// never been imported is unproven, so the round trip is tested from day one with no device involved.
dependencies {
    api(project(":core-model"))
    implementation(project(":core-export"))
    testImplementation(libs.kotlin.test)
    testImplementation(project(":core-export"))
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
