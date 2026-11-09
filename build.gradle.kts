plugins {
    kotlin("jvm") version "2.1.20"
    application
}

val ktor = "3.0.3"

dependencies {
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktor")
    testImplementation("io.ktor:ktor-client-websockets:$ktor")
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.petpal.server.MainKt") }

tasks.test { useJUnitPlatform() }
