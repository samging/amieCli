package org.example.project.components.terminal.window.controller.export

import java.io.File

/** The files of a bundle that are for the person deploying it: the start scripts, secrets.env and the README */
internal object BundleFiles {
    fun write(plan: ExportPlan, out: File) {
        File(out, "run.sh").apply { writeText(runSh(plan.manifest)); setExecutable(true, false) }
        File(out, "run.bat").writeText(runBat(plan.manifest))
        File(out, ScriptExporter.SECRETS_FILE).writeText(secretsEnv(plan.manifest))
        File(out, ".gitignore").writeText("${ScriptExporter.SECRETS_FILE}\n${ScriptExporter.AMP}/logs*.txt\n${ScriptExporter.AMP}/cache/\n")
        File(out, "README.md").writeText(readme(plan))
    }

    private fun runSh(manifest: BundleManifest) = """#!/bin/sh
# Runs this bundle. Needs Java ${manifest.javaRange}, the first of JAVA_HOME, `java` on the path and, on a Mac, the JDKs java_home
# knows. JAVA_OPTS goes to the JVM.
here=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)
java=""
found=""
try_java() {
    [ -n "${'$'}java" ] && return
    version=${'$'}("${'$'}1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1)
    [ -z "${'$'}version" ] && return
    found="${'$'}found ${'$'}version"
    if [ "${'$'}version" -ge ${manifest.javaMin} ] && [ "${'$'}version" -le ${manifest.javaMax} ]; then java=${'$'}1; fi
}
[ -n "${'$'}JAVA_HOME" ] && try_java "${'$'}JAVA_HOME/bin/java"
try_java java
[ -x /usr/libexec/java_home ] && try_java "${'$'}(/usr/libexec/java_home -v ${manifest.javaMin}+ 2>/dev/null)/bin/java"
if [ -z "${'$'}java" ]; then
    echo "This bundle needs Java ${manifest.javaRange}; found:${'$'}{found:- none}. Install a JRE or set JAVA_HOME." >&2
    exit 2
fi
exec "${'$'}java" ${'$'}JAVA_OPTS -jar "${'$'}here/run.jar" "${'$'}@"
"""

    private fun runBat(manifest: BundleManifest) = "@echo off\r\n" +
        "rem Runs this bundle. Needs Java ${manifest.javaRange}: java on the path, or JAVA_HOME.\r\n" +
        "set JAVA=java\r\n" +
        "if defined JAVA_HOME set JAVA=\"%JAVA_HOME%\\bin\\java\"\r\n" +
        "%JAVA% %JAVA_OPTS% -jar \"%~dp0run.jar\" %*\r\n"

    private fun secretsEnv(manifest: BundleManifest) = buildString {
        appendLine("# What this script needs that the export left out. One NAME=value per line; an environment variable of the")
        appendLine("# same name wins. Keep this file out of version control: the bundle's .gitignore already does.")
        if (manifest.secrets.isEmpty()) appendLine("# This script needs none.")
        manifest.secrets.forEach { secret ->
            appendLine()
            appendLine("# ${secret.why}${if (secret.required) "" else " (optional)"}")
            appendLine("${secret.name}=")
        }
    }

    private fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "${bytes shr 20} MB"
        else -> "${bytes shr 10} KB"
    }

    private fun readme(plan: ExportPlan) = buildString {
        val m = plan.manifest
        appendLine("# ${m.name}")
        appendLine()
        appendLine("A script exported from amie pilot, with what it needs to run without the app: `${m.source}`, exported ${m.exportedAt}" + (if (m.compiled) ", compiled to classes." else "."))
        appendLine()
        appendLine("## Run it")
        appendLine()
        appendLine("It needs Java ${m.javaRange} (a JRE is enough), and nothing else: no amie pilot, no Gradle, no window.")
        appendLine()
        appendLine("```sh")
        appendLine("./run.sh                       # or: java -jar run.jar; what you type is what the script's input() reads")
        if (m.templates.isNotEmpty()) {
            appendLine("./run.sh --list                # what it can run: the script and ${if (m.templates.size == 1) "its template" else "its ${m.templates.size} templates"}")
            appendLine("./run.sh --template \"${m.templateNames[m.templates.first()] ?: m.templates.first()}\"${" ".repeat((8 - (m.templateNames[m.templates.first()] ?: m.templates.first()).length).coerceAtLeast(1))}# run that template instead of the script")
        }
        appendLine("./run.sh --check               # see that it can run, without running it")
        appendLine("```")
        appendLine()
        appendLine("On Windows, `run.bat`. What the script prints is standard output, errors are standard error, and the exit code")
        appendLine("is 0 when it finished, 1 when it failed, 2 when it couldn't start. Every line, internals too, goes to")
        if (m.compiled) appendLine("`AMP/logs.txt`. The script and its templates are classes in `lib/scripts.jar`, so nothing compiles when it starts.")
        else appendLine("`AMP/logs.txt`. The script compiles when it starts, which takes some seconds on the first run of a process.")
        appendLine()
        appendLine("Lines the app writes for itself stay in that file, as they stay hidden in its console: metrics tables, charts,")
        appendLine("`sessions.print()`. `./run.sh --levels info,data,error,internal` prints them too.")
        appendLine()
        if (m.compiled) {
            appendLine("Runs on ${m.platforms.joinToString()}: ONNX Runtime and the tokenizers went in with that platform's native libraries only.")
            appendLine("For another platform, export the script there, or replace `lib/onnxruntime-*.jar` and `lib/tokenizers-*.jar` with the")
            appendLine("whole jars from Maven Central (`com.microsoft.onnxruntime:onnxruntime`, `ai.djl.huggingface:tokenizers`, the versions")
            appendLine("in the file names), which hold every platform. Linux means glibc, not Alpine. Its heap is Java's default;")
            appendLine("`JAVA_OPTS=-Xmx4g ./run.sh` gives it more.")
        } else {
            appendLine("Runs on ${m.platforms.joinToString()}, glibc Linux only (not Alpine). Its heap is Java's default; `JAVA_OPTS=-Xmx4g ./run.sh`")
            appendLine("gives it more.")
        }
        appendLine()
        appendLine("## What you supply")
        appendLine()
        if (m.secrets.isEmpty()) {
            appendLine("Nothing: this script reads no secrets.")
        } else {
            appendLine("Fill these in in `secrets.env` beside `run.sh`, or set them as environment variables, which win. The export")
            appendLine("carried none of them.")
            appendLine()
            appendLine("| Name | What for | Required |")
            appendLine("|---|---|---|")
            m.secrets.forEach { appendLine("| `${it.name}` | ${it.why} | ${if (it.required) "yes" else "no"} |") }
        }
        if (m.envVars.isNotEmpty()) {
            appendLine()
            appendLine("The script also reads: ${m.envVars.joinToString { "`$it`" }}.")
        }
        if (m.databases.isNotEmpty()) {
            appendLine()
            appendLine("## Databases")
            appendLine()
            appendLine("`AMP/databases.json` holds the connections with their passwords taken out. Change a url there if the database")
            appendLine("is somewhere else from here; a value written `\$ENV:NAME` is read from NAME, and that works in the url too.")
            appendLine()
            appendLine("| Connection | Url | User |")
            appendLine("|---|---|---|")
            m.databases.forEach { appendLine("| ${it.name} | `${it.url}` | `${it.user}` |") }
        }
        appendLine()
        appendLine("## What is in here")
        appendLine()
        appendLine("| Path | What it is |")
        appendLine("|---|---|")
        if (m.compiled) {
            appendLine("| `run.sh`, `run.bat`, `run.jar` | Start it; `run.jar` names everything in `lib/` but the scripts |")
            appendLine("| `lib/scripts.jar` | The script and its templates as classes: ${m.classes.entries.joinToString { (entry, cls) -> "`$cls` (${if (entry == BundleRunner.MAIN) "main.kts" else m.templateNames[entry] ?: entry})" }} |")
            appendLine("| `lib/` | The Kotlin runtime, coroutines, serialization, ONNX Runtime and the tokenizers for ${m.platforms.joinToString()}, the amie runtime's own classes${if (m.packages.isNotEmpty()) ", the package jars" else ""}${if (m.databases.isNotEmpty()) ", the Postgres driver" else ""}; no compiler, no UI |")
            appendLine("| `AMP/pluginDir/main.kts` | The script's text, for reading; the bundle runs its classes, so a change here changes nothing until it is exported again |")
        } else {
            appendLine("| `run.sh`, `run.bat`, `run.jar` | Start it; `run.jar` names everything in `lib/` |")
            appendLine("| `lib/` | The script engine, the amie runtime and native libraries for every platform above |")
            appendLine("| `AMP/pluginDir/main.kts` | The script. Edit it here and run again: it compiles on start |")
        }
        appendLine("| `AMP/` | What the script works on, laid out as in the app, and where it writes |")
        if (m.templates.isNotEmpty()) appendLine("| `AMP/templates/` | The templates${if (m.compiled) "' texts and metadata; `--template` runs one by its name" else ", which the script starts in the background"} |")
        if (m.encoders.isNotEmpty() || m.nerModel) appendLine("| `models/bert/` | ${listOfNotNull("the encoder${if (m.encoders.size == 1) "" else "s"} ${m.encoders.joinToString()}".takeIf { m.encoders.isNotEmpty() }, "the named-entity model".takeIf { m.nerModel }).joinToString(" and ")}; `AMIE_MODELS_DIR` points elsewhere if you keep them apart |")
        appendLine("| `secrets.env` | What you fill in |")
        appendLine("| `bundle.json` | The manifest: what went in, with each file's size and SHA-256 |")
        appendLine()
        appendLine("Env `${m.env.name}` (profile ${m.env.profile}): ${if (m.env.packages == 0) "no packages" else "${m.env.packages} package${if (m.env.packages == 1) "" else "s"}, the whole env as a Run sees it, nothing downloaded"}.")
        if (m.models.isNotEmpty()) appendLine("Models: ${m.models.joinToString { "${it.name}${it.version?.let { v -> " v$v" } ?: ""}" }}.")
        if (m.builtModels.isNotEmpty()) appendLine("Built by the script when it runs: ${m.builtModels.joinToString()}.")
        if (m.groups.isNotEmpty()) appendLine("Groups: ${m.groups.joinToString()}.")
        if (m.packages.isNotEmpty()) appendLine("Package jars: ${m.packages.joinToString()}.")
        if (m.templates.isNotEmpty()) appendLine("Templates: ${m.templates.joinToString { m.templateNames[it] ?: it }}; one the script starts keeps the process until it has ended, and `--template` runs one on its own. In the bundle they start each other as in the app.")
        if (m.data.isNotEmpty()) appendLine("Data: ${m.data.joinToString { "`AMP/$it`" }}.")
        appendLine("About ${size(plan.bytes)} in all.")
        appendLine()
        appendLine("## Deploying it")
        appendLine()
        appendLine("Copy the folder. It writes under `AMP/` (logs, models it retrains, its env), so give it a writable place. For a")
        appendLine("read-only image, keep the folder as it is and set `AMIE_AMP_DIR` to a volume: that folder is set up from `AMP/`")
        appendLine("once, keeps what it has after that, and always takes the script. Native libraries unpack under `AMP/cache/`, so")
        appendLine("that folder can't be a `noexec` mount, as Docker's plain `--tmpfs` is; `DJL_CACHE_DIR` moves them.")
        appendLine()
        appendLine("```dockerfile")
        appendLine("FROM eclipse-temurin:${m.javaMin}-jre")
        appendLine("COPY . /app")
        appendLine("ENV AMIE_AMP_DIR=/data")
        appendLine("VOLUME /data")
        appendLine("ENTRYPOINT [\"java\", \"-jar\", \"/app/run.jar\"]")
        appendLine("```")
        if (m.required.isNotEmpty()) {
            appendLine()
            appendLine("## Jars to supply")
            appendLine()
            appendLine("This bundle was exported without its jars. `${ScriptExporter.REQUIRE_FILE}` lists each file with its path under this folder; put")
            appendLine("them there before running, or under the AMP folder `AMIE_AMP_DIR` names. A Maven package the env can't find is fetched")
            appendLine("from Maven Central when the machine reaches it; a package jar is not fetched. `./run.sh --check` says which are still missing.")
            appendLine()
            appendLine("| Path | What |")
            appendLine("|---|---|")
            m.required.forEach { appendLine("| `${it.path}` | ${it.what} |") }
        }
        if (m.leftOut.isNotEmpty()) {
            appendLine()
            appendLine("## Left out")
            appendLine()
            m.leftOut.forEach { appendLine("- $it") }
        }
        if (m.missing.isNotEmpty()) {
            appendLine()
            appendLine("## Missing")
            appendLine()
            appendLine("The export went ahead without these, so the script may fail or do less:")
            appendLine()
            m.missing.forEach { appendLine("- $it") }
        }
        if (m.notes.isNotEmpty()) {
            appendLine()
            appendLine("## Notes")
            appendLine()
            m.notes.forEach { appendLine("- $it") }
        }
    }
}
