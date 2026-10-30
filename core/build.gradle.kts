import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plain Kotlin (no Android) so this logic can be unit tested on any machine.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.commons.compress)
    testImplementation(libs.kotlin.test.junit)
}
