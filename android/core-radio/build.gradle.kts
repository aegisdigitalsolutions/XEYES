plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM and interfaces-plus-mapping only. Keeping the provider contracts away from Android is
// what lets the scan scheduler, the observation factory and the session accounting be tested with
// fake providers on the JVM, and it is the reason a platform implementation cannot forget to stamp
// an observation: it never constructs one.
dependencies {
    api(project(":core-model"))
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
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
