plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":poco-core"))
}

// No framework here on purpose: the worker is a plain Kotlin program that
// happens to call the same Core library. Its scheduling, identity and
// stores are its own environment, not the API's.
application {
    mainClass = "com.poco.worker.WorkerMainKt"
}
