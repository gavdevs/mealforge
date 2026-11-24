package com.mealforge.lightphone

import java.io.BufferedReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Drives the real [MealforgeClient] against the test-only loopback fixture in
 * `apps/light-phone/contract/fixtureServer.ts`: the unchanged Hono/tRPC
 * `buildApp(db)` over an in-memory SQLite database with explicit fixture rows.
 *
 * Nothing here mocks the client, the protocol or the server. Every assertion
 * about confirmed state is re-read over HTTP from the same real server, so a
 * write only "counts" when the next `groceryItems` call reports it.
 *
 * Fixture records: plan 1 (`Fixture`, active) with generated item 1 (`Spinach`,
 * `200 g`, produce), plan 2 (`Other`, upcoming) with its own item. Each test
 * gets a fresh server + database, so mutations never leak between tests.
 */
class MealforgeClientIntegrationTest {
    private var fixture: FixtureServer? = null
    private var client: MealforgeClient? = null

    @Before
    fun startFixture() {
        val server = FixtureServer.start()
        fixture = server
        client = MealforgeClient(server.baseUrl)
    }

    @After
    fun stopFixture() {
        try {
            client?.close()
        } finally {
            fixture?.close()
        }
    }

    @Test
    fun activePlanAndGroceryItemsComeFromTheRealServer() = runBlocking {
        val mealforge = client!!

        val plan = mealforge.activePlan()
        if (plan == null) {
            fail("fixture seeds one active plan")
            return@runBlocking
        }
        assertEquals(1, plan.planId)

        val items = mealforge.groceryItems(1)
        assertEquals(1, items.size)
        val spinach = items.single()
        assertEquals(1, spinach.id)
        assertEquals(1, spinach.planId)
        assertEquals("Spinach", spinach.name)
        assertEquals("200 g", spinach.quantityText)
        assertEquals("produce", spinach.section)
        assertFalse(spinach.checked)
        assertFalse(spinach.isManual)
        assertEquals(0, spinach.sortOrder)

        // Grocery reads are scoped by plan; plan 2's row must never leak into
        // plan 1's list.
        val otherItems = mealforge.groceryItems(2)
        assertEquals(1, otherItems.size)
        assertEquals(2, otherItems.single().planId)
        assertEquals("Other plan item", otherItems.single().name)
    }

    @Test
    fun manualItemAddCheckRecheckUncheckRemoveAreConfirmedByServerReads() = runBlocking {
        val mealforge = client!!

        val added = mealforge.addManualItem(1, "Paper towels", "1 roll", "pantry")
        val itemId = added.id
        assertTrue("server assigns a positive id", itemId > 0)
        assertEquals(1, added.planId)
        assertEquals("Paper towels", added.name)
        assertEquals("1 roll", added.quantityText)
        assertEquals("pantry", added.section)
        assertFalse(added.checked)
        assertTrue(added.isManual)
        assertEquals(10_000, added.sortOrder)

        // The server, not the client, is the confirmation: read the list back.
        val afterAdd = mealforge.groceryItems(1)
        assertEquals(listOf(1, itemId), afterAdd.map { it.id })
        assertEquals(added, afterAdd.last())

        val checked = mealforge.setChecked(itemId, true)
        assertEquals(added.copy(checked = true), checked)
        assertEquals(added.copy(checked = true), mealforge.groceryItems(1).last())

        // Re-checking an already checked item stays checked; it is not a toggle.
        val rechecked = mealforge.setChecked(itemId, true)
        assertTrue(rechecked.checked)
        assertTrue(mealforge.groceryItems(1).last().checked)

        val unchecked = mealforge.setChecked(itemId, false)
        assertFalse(unchecked.checked)
        assertEquals(added, unchecked)
        assertEquals(added, mealforge.groceryItems(1).last())

        mealforge.removeManualItem(itemId)
        val afterRemove = mealforge.groceryItems(1)
        assertEquals(listOf(1), afterRemove.map { it.id })
        assertEquals("Spinach", afterRemove.single().name)
    }

    @Test
    fun manualItemDefaultsComeFromTheServer() = runBlocking {
        val mealforge = client!!

        val added = mealforge.addManualItem(1, "  Bananas  ", null, null)
        assertEquals("Bananas", added.name)
        assertEquals("", added.quantityText)
        assertEquals("other", added.section)
        assertTrue(added.isManual)
        assertFalse(added.checked)
        assertEquals(10_000, added.sortOrder)

        // Defaults survive a real read, and the row belongs to plan 1 only.
        val stored = mealforge.groceryItems(1).single { it.id == added.id }
        assertEquals(added, stored)
        assertTrue(mealforge.groceryItems(2).none { it.id == added.id })

        mealforge.removeManualItem(added.id)
        assertTrue(mealforge.groceryItems(1).none { it.id == added.id })
    }

    @Test
    fun generatedItemRemovalSurfacesTheServerRefusalAndKeepsTheItem() = runBlocking {
        val mealforge = client!!

        assertServerFailure("Only manually added items can be removed from the list.") {
            mealforge.removeManualItem(1)
        }

        // The refusal must not have deleted anything.
        val items = mealforge.groceryItems(1)
        assertEquals(listOf(1), items.map { it.id })
        assertFalse(items.single().isManual)
    }

    @Test
    fun missingCheckTargetSurfacesThePlainServerError() = runBlocking {
        val mealforge = client!!

        assertServerFailure("Grocery item 99 does not exist.") {
            mealforge.setChecked(99, true)
        }

        val items = mealforge.groceryItems(1)
        assertEquals(listOf(1), items.map { it.id })
        assertFalse("a server error must not confirm a write", items.single().checked)
    }

    @Test
    fun invalidCheckTargetFailsVisiblyWithoutConfirmingAWrite() = runBlocking {
        val mealforge = client!!

        // itemId 0 fails the router's Zod schema (HTTP 400). Whether the client
        // or the server rejects it, the call must fail with a message rather
        // than returning a fabricated row.
        assertServerFailure {
            mealforge.setChecked(0, true)
        }

        val items = mealforge.groceryItems(1)
        assertEquals(listOf(1), items.map { it.id })
        assertFalse(items.single().checked)
    }

    /**
     * Asserts the call fails with a message, and — when [expectedMessage] is
     * given — that the server's text reaches the caller, unwrapping
     * client/transport exception chains so the real backend message is kept.
     */
    private suspend fun assertServerFailure(
        expectedMessage: String? = null,
        block: suspend () -> Unit,
    ) {
        val failure = runCatching { block() }.exceptionOrNull()
        if (failure == null) {
            fail("expected the server to reject the request")
            return
        }
        val messages = generateSequence(failure) { it.cause }.mapNotNull { it.message }.toList()
        assertTrue("failure carried no message: $failure", messages.any { it.isNotBlank() })
        if (expectedMessage != null) {
            assertTrue(
                "expected server message containing '$expectedMessage', got $messages",
                messages.any { it.contains(expectedMessage) },
            )
        }
    }

    /** Test-only launcher for `contract/fixtureServer.ts` on loopback. */
    private class FixtureServer private constructor(
        private val process: Process,
        val baseUrl: String,
    ) : AutoCloseable {
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
            private const val READY_TIMEOUT_SECONDS = 60L
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

                // Run from apps/api so `--import tsx` and the API's own
                // dependencies resolve exactly as they do for its tests.
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

                val stdoutLines = LinkedBlockingQueue<String>()
                val seenStdout = StringBuilder()
                val seenStderr = StringBuilder()

                daemonReader("fixture-stdout", process.inputStream.bufferedReader()) { line ->
                    synchronized(seenStdout) { seenStdout.appendLine(line) }
                    stdoutLines.offer(line)
                }
                daemonReader("fixture-stderr", process.errorStream.bufferedReader()) { line ->
                    synchronized(seenStderr) { seenStderr.appendLine(line) }
                }

                try {
                    val url = awaitUrl(process, stdoutLines) { message ->
                        buildString {
                            append(message)
                            append("\nstdout: ")
                            append(synchronized(seenStdout) { seenStdout.toString() })
                            append("\nstderr: ")
                            append(synchronized(seenStderr) { seenStderr.toString() })
                        }
                    }
                    return FixtureServer(process, url)
                } catch (error: Throwable) {
                    process.destroyForcibly()
                    throw error
                }
            }

            /** Waits (bounded) for the fixture's single `{"url": ...}` line. */
            private fun awaitUrl(
                process: Process,
                stdoutLines: LinkedBlockingQueue<String>,
                describe: (String) -> String,
            ): String {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(READY_TIMEOUT_SECONDS)
                while (true) {
                    val remainingMs = TimeUnit.NANOSECONDS
                        .toMillis(deadline - System.nanoTime())
                        .coerceAtMost(1_000L)
                    if (remainingMs <= 0) {
                        val message = "fixture server reported no URL within $READY_TIMEOUT_SECONDS s"
                        throw AssertionError(describe(message))
                    }
                    val line = stdoutLines.poll(remainingMs, TimeUnit.MILLISECONDS) ?: continue
                    val url = parseUrl(line) ?: continue
                    if (!process.isAlive) {
                        throw AssertionError(describe("fixture server exited after printing $url"))
                    }
                    if (!url.startsWith("http://127.0.0.1:")) {
                        throw AssertionError(describe("fixture must bind loopback only, got $url"))
                    }
                    return url
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
            ) {
                Thread({ reader.useLines { lines -> lines.forEach(onLine) } }, name).apply {
                    isDaemon = true
                    start()
                }
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
}
