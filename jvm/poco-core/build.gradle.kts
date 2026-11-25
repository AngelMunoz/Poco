plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
}

// Plain Kotlin library on purpose: no Spring, no server, no I/O. If this
// module ever needs a framework dependency, that is a design discussion.
dependencies {
    // suspend contracts are part of Core's public API, hence `api`: consumers
    // must see coroutines to call the asynchronous operations.
    api(libs.coroutines.core)
}
