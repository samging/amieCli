import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The amie interpreter: the engine module (runtime) at a terminal, with no window and nothing of the app on its class
 * path. bin/amie at the repository root builds and runs it; install.sh puts it on the PATH.
 */
plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

// The interpreter's version
val amieVersion = "1.0.0"

dependencies {
    implementation(project(":runtime"))
    // Line editing and history at the REPL's prompt
    implementation("org.jline:jline:3.27.1")
    // The engine's loggers write through slf4j-simple, at the levels Output.install sets
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "org.amie.cli.MainKt"
    applicationName = "amie"
    applicationDefaultJvmArgs = listOf(
        "-Damie.version=$amieVersion",
        "-Djava.awt.headless=true",
        // The heap may grow to 35% of the machine's RAM, as the app's; the encoders load outside it
        "-XX:MaxRAMPercentage=35",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
    ) + (System.getenv("AMIE_JVM_ARGS")?.split(' ')?.filter { it.isNotBlank() } ?: emptyList())
}

// The start script Gradle writes takes the java JAVA_HOME or the PATH names, whatever its version; amie needs a 21,
// and jdk.sh finds one first. ./amie at the repository root and install.sh source the same file.
// The learn book (learn/index.html) rides in the jar: :learn and :docs copy it to AMP/learn and open it
tasks.processResources {
    from("learn") { into("learn") }
}

tasks.startScripts {
    val jdk = file("jdk.sh")
    inputs.file(jdk)
    doLast {
        val mark = "# Determine the Java command to use to start the JVM."
        unixScript.writeText(unixScript.readText().replace(mark, jdk.readText() + "\n" + mark))
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    maxHeapSize = "1g"
    // A folder of its own, never the user's AMP
    environment("AMIE_AMP_DIR", layout.buildDirectory.dir("test-amp").get().asFile.path)
    environment("AMIE_BACKEND_URL", "http://backend.test")
    environment("AMIE_SITE_URL", "http://site.test")
    environment("AMIE_S3_URL", "http://s3.test")
    environment("KEYCLOAK_URL", "http://keycloak.test")
    environment("AMIE_CLOUD_SAVE", "off")
}
