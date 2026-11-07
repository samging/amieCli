package org.example.project.components.terminal.window.controller.databases

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.example.project.components.terminal.window.controller.LogLevel
import org.example.project.components.terminal.window.controller.console.logData
import org.example.project.components.terminal.window.controller.console.logInternal
import org.example.project.getDirectory
import org.example.project.util.Secrets
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger

/**
 * One entry of `AMP/databases.json`: a JDBC url with its user and password. A url, user or password written as
 * `$ENV:NAME` is read from the environment variable NAME when the connection is opened, so the file can stay
 * free of secrets.
 */
@Serializable
data class DatabaseConfig(
    val url: String = "jdbc:postgresql://localhost:5432/postgres",
    val user: String = "postgres",
    val password: String = "\$ENV:PGPASSWORD",
    val description: String = "",
) {
    fun resolved(value: String): String = Secrets.resolve(value)
}

/** What a script sees when a connection or a statement fails: the database's name, the reason, the SQL state and the statement */
class DatabaseException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * A configured database as a script uses it: `query` for rows, `execute` for changes, `print` to see rows in the
 * console, `tables()` for what is there, `close()` when done (or leave it: the app closes it on exit). The
 * connection opens on the first statement and is reopened when it drops. Every failure is one ERROR line in
 * the console and a [DatabaseException] the script can catch.
 */
class Database internal constructor(val name: String, val config: DatabaseConfig) : AutoCloseable {
    private var connection: Connection? = null
    private val statements = AtomicInteger()

    /** Statements run on this connection so far */
    val runs: Int get() = statements.get()
    val open: Boolean get() = connection?.let { runCatching { !it.isClosed }.getOrDefault(false) } == true

    @Synchronized
    private fun connection(): Connection {
        connection?.takeIf { runCatching { !it.isClosed && it.isValid(2) }.getOrDefault(false) }?.let { return it }
        connection?.let { runCatching { it.close() } }
        val user = config.resolved(config.user)
        val fresh = try {
            DriverManager.getConnection(config.resolved(config.url), user, config.resolved(config.password))
        } catch (e: SQLException) {
            throw fail("can't connect to ${config.url} as '$user': ${e.message?.trim()}", e)
        }
        logInternal("database '$name': connected to ${config.url} (${runCatching { "${fresh.metaData.databaseProductName} ${fresh.metaData.databaseProductVersion}" }.getOrDefault("unknown server")})")
        connection = fresh
        return fresh
    }

    /** The rows of a SELECT, each a map of column label to value in the query's column order; `?` placeholders take [params] */
    fun query(sql: String, vararg params: Any?): List<Map<String, Any?>> = run(sql, params) { statement ->
        statement.executeQuery().use { rs ->
            val meta = rs.metaData
            val columns = (1..meta.columnCount).map { meta.getColumnLabel(it) }
            val rows = mutableListOf<Map<String, Any?>>()
            while (rs.next()) rows += LinkedHashMap<String, Any?>().apply { columns.forEachIndexed { i, column -> put(column, rs.getObject(i + 1)) } }
            rows
        }
    }

    /** One value: the first column of the first row, null when there is no row */
    fun scalar(sql: String, vararg params: Any?): Any? = query(sql, *params).firstOrNull()?.values?.firstOrNull()

    /** INSERT, UPDATE, DELETE or DDL; the number of rows it touched */
    fun execute(sql: String, vararg params: Any?): Int = run(sql, params) { it.executeUpdate() }

    /** The rows of a SELECT, printed in the console as a table (DATA lines, at most [limit] rows) and returned */
    fun print(sql: String, vararg params: Any?, limit: Int = 200): List<Map<String, Any?>> {
        val rows = query(sql, *params)
        logTable(rows, limit)
        return rows
    }

    /** The tables of the database as schema.table, the system schemas left out */
    fun tables(): List<String> = query(
        "select table_schema || '.' || table_name as name from information_schema.tables " +
            "where table_schema not in ('pg_catalog', 'information_schema') order by 1"
    ).map { it["name"].toString() }

    private fun <T> run(sql: String, params: Array<out Any?>, block: (PreparedStatement) -> T): T {
        val begun = System.nanoTime()
        try {
            connection().prepareStatement(sql).use { statement ->
                params.forEachIndexed { i, p -> statement.setObject(i + 1, p) }
                val result = block(statement)
                statements.incrementAndGet()
                logInternal("database '$name': ${sql.trim().replace(Regex("\\s+"), " ").take(90)} (${(System.nanoTime() - begun) / 1_000_000} ms)")
                return result
            }
        } catch (e: SQLException) {
            throw fail("${e.message?.trim()}${e.sqlState?.let { " [$it]" } ?: ""} in: ${sql.trim().replace(Regex("\\s+"), " ").take(120)}", e)
        }
    }

    private fun fail(message: String, cause: Throwable): DatabaseException {
        val full = "database '$name': $message"
        logInternal(full, LogLevel.ERROR)
        return DatabaseException(full, cause)
    }

    private fun logTable(rows: List<Map<String, Any?>>, limit: Int) {
        if (rows.isEmpty()) { logData("(no rows)", DB_SOURCE); return }
        val columns = rows.first().keys.toList()
        val shown = rows.take(limit)
        val widths = columns.map { c -> maxOf(c.length, shown.maxOf { (it[c]?.toString() ?: "null").length }).coerceAtMost(60) }
        fun cell(value: Any?, width: Int) = (value?.toString() ?: "null").replace('\n', ' ').take(width).padEnd(width)
        val lines = buildList {
            add(columns.mapIndexed { i, c -> c.take(widths[i]).padEnd(widths[i]) }.joinToString("  "))
            add(widths.joinToString("  ") { "-".repeat(it) })
            shown.forEach { row -> add(columns.mapIndexed { i, c -> cell(row[c], widths[i]) }.joinToString("  ")) }
            if (rows.size > limit) add("… ${rows.size - limit} more rows")
        }
        logData(lines.joinToString("\n"), DB_SOURCE)
    }

    @Synchronized
    override fun close() {
        connection?.let {
            runCatching { it.close() }
            logInternal("database '$name': closed after $runs statements")
        }
        connection = null
    }

    override fun toString(): String = "$name · ${config.url} · ${if (open) "open" else "closed"} · $runs statements"
}

private const val DB_SOURCE = "sandbox/database"

/**
 * The databases a script can open, configured in `AMP/databases.json` as `{"name": {url, user, password,
 * description}}`. The file is written with one editable `main` entry on first use. `open(name)` returns the
 * same [Database] for the same name while the app runs; `closeAll()` at the end of a script, or on exit, closes them.
 */
object Databases {
    val file: File get() = File(getDirectory().toString(), "databases.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }
    private val opened = linkedMapOf<String, Database>()

    /** Writes the template unless the file exists */
    fun ensure(): File {
        if (file.isFile) return file
        val template = mapOf(
            "main" to DatabaseConfig(description = "Edit the url, user and password, or add more entries; a script opens this one with database(\"main\"). \$ENV:NAME reads an environment variable."),
        )
        runCatching { file.parentFile?.mkdirs(); file.writeText(json.encodeToString(template)) }
            .onSuccess { logInternal("databases: ${file.path} written with a 'main' entry to edit") }
            .onFailure { logInternal("databases: ${file.path} could not be written: ${it.message}", LogLevel.ERROR) }
        return file
    }

    fun configs(): Map<String, DatabaseConfig> {
        ensure()
        return runCatching { json.decodeFromString<Map<String, DatabaseConfig>>(file.readText()) }
            .getOrElse { logInternal("databases: ${file.path} can't be read: ${it.message}", LogLevel.ERROR); emptyMap() }
    }

    fun names(): List<String> = configs().keys.toList()

    /** The database of that name in databases.json; the one already open when there is one */
    @Synchronized
    fun open(name: String = "main"): Database {
        opened[name]?.let { return it }
        val config = configs()[name] ?: throw DatabaseException(
            "database '$name' is not in ${file.path}" + (names().takeIf { it.isNotEmpty() }?.let { "; configured: ${it.joinToString()}" } ?: "")
        ).also { logInternal(it.message!!, LogLevel.ERROR) }
        return Database(name, config).also { opened[name] = it }
    }

    /** One line per configured database: name, url, user, whether it is open and how many statements it ran */
    fun print(): List<String> {
        val lines = configs().map { (name, config) ->
            val db = synchronized(this) { opened[name] }
            "$name  ${config.url}  as ${config.resolved(config.user).ifEmpty { "?" }}  ${db?.let { if (it.open) "open · ${it.runs} statements" else "closed · ${it.runs} statements" } ?: "not opened"}" +
                (config.description.takeIf { it.isNotBlank() }?.let { "  · $it" } ?: "")
        }.ifEmpty { listOf("no databases in ${file.path}") }
        logInternal(lines.joinToString("\n"))
        return lines
    }

    @Synchronized
    fun closeAll() {
        opened.values.forEach { it.close() }
        opened.clear()
    }
}
