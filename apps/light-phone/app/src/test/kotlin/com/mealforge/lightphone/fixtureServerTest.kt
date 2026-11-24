package com.mealforge.lightphone

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [FixtureServer.awaitReady], the seam that turns the fixture
 * child's stdout into a base URL.
 *
 * These tests never touch the real fixture, dependencies, or the app: each one
 * runs a tiny `/bin/sh` child so readiness, failure, and cleanup behavior are
 * deterministic.
 */
class FixtureServerTest {

    @Test
    fun awaitReadyFailsFastWhenChildExitsWithDiagnostic() {
        val process = launchShell(
            """
            printf 'fixture: database unavailable\n' >&2
            exit 23
            """.trimIndent(),
        )
        val startedAt = System.nanoTime()
        val failure = runCatching {
            FixtureServer.awaitReady(process, timeoutMillis = 60_000)
        }.exceptionOrNull()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        try {
            assertTrue(
                "awaitReady accepted a child that exited with code 23",
                failure is AssertionError,
            )
            val message = failure!!.message.orEmpty()
            assertTrue(
                "an exited child must fail well before the 60 s deadline, took ${elapsedMs}ms",
                elapsedMs < 10_000,
            )
            assertTrue(
                "diagnostic must name the child's exit code, was: $message",
                Regex("exit\\D*23").containsMatchIn(message),
            )
            assertTrue(
                "diagnostic must name the setup prerequisite, was: $message",
                message.contains("pnpm install --frozen-lockfile"),
            )
            assertFalse("the failed child must not be left running", process.isAlive)
        } finally {
            // Only reached if the seam left the child behind.
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun awaitReadyReturnsLoopbackUrlFromLiveChild() {
        val process = launchShell(
            """
            printf '{"url":"http://127.0.0.1:47831"}\n'
            exec sleep 60
            """.trimIndent(),
        )

        try {
            val url = FixtureServer.awaitReady(process, timeoutMillis = 10_000)
            assertEquals("http://127.0.0.1:47831", url)
            assertTrue(
                "readiness must not stop the fixture: it stays alive while serving",
                process.isAlive,
            )
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun awaitReadyRejectsNonLoopbackUrlAndStopsChild() {
        val process = launchShell(
            """
            printf '{"url":"http://0.0.0.0:47832"}\n'
            exec sleep 60
            """.trimIndent(),
        )

        try {
            val failure = runCatching {
                FixtureServer.awaitReady(process, timeoutMillis = 10_000)
            }.exceptionOrNull()

            assertTrue(
                "awaitReady must reject a URL that is not bound to loopback",
                failure is AssertionError,
            )
            val message = failure!!.message.orEmpty()
            assertTrue(
                "diagnostic must point at the offending URL or the loopback rule, was: $message",
                message.contains("0.0.0.0") || message.contains("loopback"),
            )
        } finally {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun launchShell(script: String): Process =
        ProcessBuilder("/bin/sh", "-c", script).start()
}
