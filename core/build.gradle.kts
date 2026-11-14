plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(libs.coroutines.core)
    api(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

tasks.test {
    // Live tests against the real website run only when explicitly requested: LIVE=1 ./gradlew :core:test
    environment("LIVE", System.getenv("LIVE") ?: "")
    testLogging { events("passed", "skipped", "failed"); showStandardStreams = true }
}
