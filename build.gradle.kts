plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
    application
}

group = "com.helltar"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dotenv.kotlin)

    implementation(libs.kotlin.logging.jvm)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.helltar.varta.MainKt")

    applicationDefaultJvmArgs = listOf("-Dkotlin-logging.logStartupMessage=false")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
