plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.marketdata"
version = "1.0.0"

repositories { mavenCentral() }

application { mainClass.set("com.marketdata.MainKt") }

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

tasks {
    named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
        archiveBaseName.set("market-data-server")
        archiveClassifier.set("")
        archiveVersion.set("")
        mergeServiceFiles()
        manifest { attributes("Main-Class" to "com.marketdata.MainKt") }
    }
    build { dependsOn("shadowJar") }
}

dependencies {
    val ktor_version = "3.2.0"

    implementation("io.ktor:ktor-server-core:$ktor_version")
    implementation("io.ktor:ktor-server-jetty:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
    implementation("io.ktor:ktor-server-cors:$ktor_version")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("io.github.microutils:kotlin-logging-jvm:3.0.5")
    implementation("ch.qos.logback:logback-classic:1.4.11")
    implementation("com.zaxxer:HikariCP:6.0.0")
    implementation("org.postgresql:postgresql:42.7.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
