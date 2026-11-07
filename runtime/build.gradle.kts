import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * The engine: the script runtime and everything a script reaches (the models and their store, groups, packages, envs,
 * templates, the Hub imports, the model server, the AMP folder), with no window. The amie interpreter runs on it, as
 * the amie pilot desktop app does in its own repository; nothing here references Compose, Swing or Jewel.
 */
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
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

// The lightweight build: no TensorFlow 1.15, KotlinDL or its JavaCPP (~700 MB of jars, mostly natives for other
// systems). Training and serving are the engine's own Kotlin heads on ONNX Runtime and need none of it. It has DJL's
// TensorFlow 2 engine instead, which opens SavedModels with importModel and initMod and whose jar brings
// org.tensorflow classes of its own, so the full build, which keeps TensorFlow 1.15, can't have it. The default on
// arm64 Linux and on an Apple Silicon Mac, where TensorFlow 1.15 has no natives anyway; -Pamie.lightweight=true|false
// chooses on any machine. The desktop module reads the same property for its launcher.
val linuxArm64 = System.getProperty("os.name").lowercase().contains("linux") && System.getProperty("os.arch") in setOf("aarch64", "arm64")
val macArm64 = System.getProperty("os.name").lowercase().contains("mac") && System.getProperty("os.arch") in setOf("aarch64", "arm64")
val lightweight = (findProperty("amie.lightweight") as String?)?.toBoolean() ?: (linuxArm64 || macArm64)

kotlin {
    sourceSets.named("main") {
        if (lightweight) {
            // The one file that needs TensorFlow 1.15
            kotlin.exclude("**/amiePilot/NNHelpers.kt")
        }
    }
}

// What scripts and the modules on top see (a Tensor, an ONNX session, a coroutine scope) is api; the rest is the engine's own
dependencies {
    api(libs.kotlin.scripting.jsr223)
    api(libs.kotlin.compiler.embeddable)
    // The script compiler's own API, which the JSR223 engine brings only at run time: the export compiles scripts to
    // classes with it (CompiledScripts), and a compiled bundle carries none of these
    api("org.jetbrains.kotlin:kotlin-scripting-common:${libs.versions.kotlin.get()}")
    api("org.jetbrains.kotlin:kotlin-scripting-jvm:${libs.versions.kotlin.get()}")
    api("org.jetbrains.kotlin:kotlin-scripting-jvm-host:${libs.versions.kotlin.get()}")
    api("org.jetbrains.kotlin:kotlin-scripting-compiler-embeddable:${libs.versions.kotlin.get()}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${libs.versions.kotlinx.coroutines.get()}")
    api(libs.kotlinx.serialization.json)
    api(libs.okio)
    api("org.slf4j:slf4j-api:${libs.versions.slf4j.get()}")
    // The Hugging Face Hub, the package store and the backend are asked over HTTP
    api(libs.ktor.client.core)
    api(libs.ktor.client.java)
    api(libs.ktor.client.content.negotiation)
    api(libs.ktor.serialization.kotlinx.json)
    // The model engines: intent models on ONNX Runtime with the Hub's tokenizers; models from outside (importModel,
    // initMod of AMP/models/imported) as TorchScript on DJL's PyTorch engine, whose natives (40 to 110 MB) are
    // downloaded into ~/.djl.ai on the first use on a machine
    api("com.microsoft.onnxruntime:onnxruntime:1.31.1")
    api("ai.djl.huggingface:tokenizers:0.31.0")
    api("ai.djl:api:0.31.0")
    api("ai.djl.pytorch:pytorch-engine:0.31.0")
    if (lightweight) {
        // TensorFlow SavedModels on DJL's TensorFlow 2 engine, natives downloaded on first use (130 to 150 MB)
        api("ai.djl.tensorflow:tensorflow-engine:0.31.0")
    } else {
        api(libs.tensorflow.core)
        api(libs.tensorflow.platform) {
            exclude(group = "org.bytedeco", module = "javacpp")
        }
        api("org.jetbrains.kotlinx:kotlin-deeplearning-api:0.5.0")
        api("org.jetbrains.kotlinx:kotlin-deeplearning-tensorflow:0.5.0")
        api("org.bytedeco:javacpp:1.5.8")
    }
}
