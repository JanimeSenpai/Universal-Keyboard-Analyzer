plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
    id("org.jetbrains.compose") version "1.7.3"
}

group = "org.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.moeaframework:moeaframework:4.2")
    implementation("org.cicirello:chips-n-salsa:7.0.2")
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}