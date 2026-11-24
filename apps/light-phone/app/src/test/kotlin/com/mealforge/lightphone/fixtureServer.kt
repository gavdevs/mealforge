package com.mealforge.lightphone

import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Test-only launcher for `contract/fixtureServer.ts`: the unchanged Hono/tRPC
 * app over in-memory SQLite with the shared fixture rows (plans 1-4, recipes
 * 1-2, meals 1-5, grocery rows 1-2).
 *
 * The server also exposes its `/__fixture/` bridge, which lets a test hold one
 * named request (so duplicate-tap and late-response behavior is deterministic),
 * release it, and count the requests that reached the real app.
 *
 * Each call to [start] runs its own process and database, so mutations never
 * leak between tests.
 */
internal class FixtureServer private constructor(
    private val process: Process,
    val baseUrl: String,
) : AutoCloseable {

    /** Holds the next [method] request for [procedure] before the real app sees it. */
    fun gateNext(procedure: String, method: String = "POST") {
        bridge(
            "/__fixture/gate",
            buildJsonObject {
                put("procedure", procedure)
                put("method", method)
            }.toString(),
        )
    }

    /** Waits (bounded) until the armed gate holds a request. */
    fun awaitGateStarted(timeoutMs: Long = 10_000) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val status = Json.parseToJsonElement(bridge("/__fixture/status")).jsonObject
            if (status["started"]?.jsonPrimitive?.boolean == true) return
            if (System.nanoTime() >= deadline) {
                throw AssertionError("no gated request reached the fixture within $timeoutMs ms")
            }
            Thread.sleep(20)
        }
    }

    fun releaseGate() {
        bridge("/__fixture/release", "{}")
    }

    /** Requests the real app received, filtered by HTTP method + procedure path. */
    fun requestCount(method: String, procedure: String): Int {
        val requests = Json.parseToJsonElement(bridge("/__fixture/requests"))
            .jsonObject["requests"]?.jsonArray ?: return 0
        return requests.count { entry ->
            val request = entry.jsonObject
            request["method"]?.jsonPrimitive?.contentOrNull == method &&
                request["path"]?.jsonPrimitive?.contentOrNull == "/trpc/$procedure"
        }
    }

    private fun bridge(path: String, body: String? = null): String {
        val connection = (URI(baseUrl + path).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = if (body == null) "GET" else "POST"
            connectTimeout = 5_000
            readTimeout = 5_000
            if (body != null) {
                doOutput = true
                setRequestProperty("content-type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val payload = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "fixture bridge $path failed with HTTP $status: $payload" }
            return payload
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        if (!process.isAlive) return
        // SIGTERM: the fixture closes its listener and SQLite handle.
        process.destroy()
        if (!process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    companion object {
        private const val SHUTDOWN_TIMEOUT_SECONDS = 10L

        fun start(): FixtureServer {
            val repoRoot = findRepoRoot()
            val apiDir = repoRoot.resolve("apps/api")
            val fixtureScript = repoRoot.resolve("apps/light-phone/contract/fixtureServer.ts")
            if (!Files.isRegularFile(apiDir.resolve("package.json"))) {
                throw AssertionError("apps/api not found under repo root $repoRoot")
            }
            if (!Files.isRegularFile(fixtureScript)) {
                throw AssertionError("fixture server missing at $fixtureScript")
            }

            // Run from apps/api so `--import tsx` and the API's own dependencies
            // resolve exactly as they do for its tests.
            val process = try {
                ProcessBuilder(
                    "node",
                    "--import",
                    "tsx",
                    fixtureScript.toAbsolutePath().toString(),
                )
                    .directory(apiDir.toFile())
                    .start()
            } catch (error: IOException) {
                throw AssertionError(
                    "could not start 'node --import tsx $fixtureScript' from $apiDir: " +
                        error.message,
                    error,
                )
            }
            return FixtureServer(process, awaitReady(process))
        }

        internal fun awaitReady(process: Process, timeoutMillis: Long = 60_000): String {
            val stdoutLines = LinkedBlockingQueue<String>()
            val seenStdout = StringBuilder()
            val seenStderr = StringBuilder()
            val stdoutReader = daemonReader("fixture-stdout", process.inputStream.bufferedReader()) { line ->
                synchronized(seenStdout) { if (seenStdout.length < 8192) seenStdout.appendLine(line.take(8192)) }
                stdoutLines.offer(line)
            }
            val stderrReader = daemonReader("fixture-stderr", process.errorStream.bufferedReader()) { line ->
                synchronized(seenStderr) { if (seenStderr.length < 8192) seenStderr.appendLine(line.take(8192)) }
            }
            fun describe(message: String): String = buildString {
                appendLine(message)
                appendLine("Fixture prerequisite: pnpm install --frozen-lockfile from the repository root.")
                appendLine("stdout: " + synchronized(seenStdout) { seenStdout.toString() })
                append("stderr: " + synchronized(seenStderr) { seenStderr.toString() })
            }
            try {
                val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
                while (true) {
                    if (!process.isAlive) {
                        stdoutReader.join(500)
                        stderrReader.join(500)
                        throw AssertionError(describe("Fixture exited with exit code ${process.exitValue()} before readiness."))
                    }
                    val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (remaining <= 0) throw AssertionError(describe("Fixture reported no URL within $timeoutMillis ms."))
                    val line = stdoutLines.poll(minOf(remaining, 100), TimeUnit.MILLISECONDS) ?: continue
                    val url = parseUrl(line) ?: continue
                    val uri = URI(url)
                    if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port !in 1..65535 ||
                        uri.rawUserInfo != null) {
                        throw AssertionError(describe("Fixture must bind loopback only, got $url"))
                    }
                    if (!process.isAlive) throw AssertionError(describe("Fixture exited with exit code ${process.exitValue()} after readiness."))
                    return url
                }
            } catch (failure: Throwable) {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
                throw failure
            }
        }

        private fun parseUrl(line: String): String? = try {
            Json.parseToJsonElement(line).jsonObject["url"]?.jsonPrimitive?.contentOrNull
        } catch (_: IllegalArgumentException) {
            null
        }

        private fun daemonReader(
            name: String,
            reader: BufferedReader,
            onLine: (String) -> Unit,
        ): Thread = Thread({ reader.useLines { lines -> lines.forEach(onLine) } }, name).apply {
            isDaemon = true
            start()
        }

        /**
         * Gradle unit tests run with the app module as the working directory
         * (`apps/light-phone/app`), so the repo root is three levels up; walk
         * up rather than hardcoding so other launchers still work.
         */
        private fun findRepoRoot(): Path {
            var candidate: Path? = Path.of("").toAbsolutePath().normalize()
            while (candidate != null) {
                if (
                    Files.isRegularFile(candidate.resolve("pnpm-workspace.yaml")) &&
                    Files.isRegularFile(candidate.resolve("apps/api/package.json"))
                ) {
                    return candidate
                }
                candidate = candidate.parent
            }
            throw AssertionError(
                "could not locate the MealForge repo root from ${Path.of("").toAbsolutePath()}",
            )
        }
    }
}
