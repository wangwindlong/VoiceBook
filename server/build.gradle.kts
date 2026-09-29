plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    application
}

group = "us.wangxy.voicebook"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "us.wangxy.voicebook.server.bff.ApplicationKt"
    // installDist output lands in server/build/install/bff (consumed by server/deploy/Dockerfile).
    applicationName = "bff"
    applicationDefaultJvmArgs = listOf(
        "-XX:MaxRAMPercentage=70",
        "-XX:+UseSerialGC",
        "-Dfile.encoding=UTF-8",
    )
}

dependencies {
    implementation(projects.core.apiContract)

    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.unboundid.ldapsdk)
    implementation(libs.sqlite.jdbc)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.mock)
}

tasks.test {
    useJUnitPlatform()
}

// The explicit jar list exceeds cmd.exe's line limit; a wildcard keeps bin\bff.bat runnable on Windows.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val script = (this as CreateStartScripts).windowsScript
        script.writeText(script.readText().replace(Regex("(?m)^set CLASSPATH=.*$"), "set CLASSPATH=%APP_HOME%\\\\lib\\\\*"))
    }
}

// `./gradlew :server:run` picks up server/.env.local (same keys as deploy/.env.example, gitignored).
tasks.named<JavaExec>("run") {
    val envFile = layout.projectDirectory.file(".env.local").asFile
    doFirst {
        if (envFile.isFile) {
            envFile.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
                .forEach { (this as JavaExec).environment(it.substringBefore('=').trim(), it.substringAfter('=').trim()) }
        }
    }
}
