package com.mealforge.lightphone

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Disposable tRPC-over-HTTP stub for the grocery store tests.
 *
 * This is a real external HTTP boundary, not a mock of application code: the
 * client under test uses its normal Ktor/OkHttp path. The transport is a plain
 * loopback [ServerSocket] speaking minimal HTTP/1.1 with one request per
 * connection, because the Android unit-test classpath has no
 * `com.sun.net.httpserver` module. Fixtures are mutable so a mutation followed
 * by a refetch observes the same server state, matching the deployed API's
 * non-batched request/response envelopes.
 */
internal class GroceryTestServer : Closeable {
    data class TestPlan(
        val planId: Int,
        val displayName: String,
        val status: String = "active",
    )

    data class TestItem(
        val id: Int,
        val planId: Int,
        val name: String,
        val quantityText: String,
        val section: String,
        val checked: Boolean,
        val isManual: Boolean,
        val sortOrder: Int,
    )

    data class RecordedRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val body: String,
    ) {
        fun inputJson(): JsonObject? =
            query["input"]?.let { Json.parseToJsonElement(it).jsonObject }

        fun bodyJson(): JsonObject = Json.parseToJsonElement(body).jsonObject
    }

    private class Refusal(message: String) : RuntimeException(message)

    private val lock = Any()
    private val gateLock = Any()

    private val requestLog = CopyOnWriteArrayList<RecordedRequest>()
    private val mutableItems = mutableListOf<TestItem>()
    private var testPlan: TestPlan? = null
    private var nextManualId = 100
    private var pendingItemsGate: Pair<CountDownLatch, CountDownLatch>? = null
    private var pendingPostGate: Pair<String, Pair<CountDownLatch, CountDownLatch>>? = null

    @Volatile
    var failSetCheckedMessage: String? = null

    private val workers = Executors.newCachedThreadPool()
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    init {
        Thread({ acceptLoop() }, "grocery-test-server").apply {
            isDaemon = true
            start()
        }
    }

    val baseUrl: String
        get() = "http://127.0.0.1:${serverSocket.localPort}"

    val requests: List<RecordedRequest>
        get() = requestLog.toList()

    fun plan(): TestPlan? = synchronized(lock) { testPlan }

    fun setPlan(plan: TestPlan?) {
        synchronized(lock) { testPlan = plan }
    }

    fun items(): List<TestItem> = synchronized(lock) { mutableItems.toList() }

    fun setItems(items: List<TestItem>) {
        synchronized(lock) {
            mutableItems.clear()
            mutableItems.addAll(items)
        }
    }

    fun posts(procedure: String): List<RecordedRequest> =
        requests.filter { it.method == "POST" && it.path == "/trpc/$procedure" }

    fun gets(procedure: String): List<RecordedRequest> =
        requests.filter { it.method == "GET" && it.path == "/trpc/$procedure" }

    /** Holds the next grocery.itemsForPlan read until [release] counts down. */
    fun gateNextItemsRead(started: CountDownLatch, release: CountDownLatch) {
        synchronized(gateLock) { pendingItemsGate = started to release }
    }

    /** Holds the next POST to [procedure] until [release] counts down. */
    fun gateNextPost(procedure: String, started: CountDownLatch, release: CountDownLatch) {
        synchronized(gateLock) { pendingPostGate = procedure to (started to release) }
    }

    private fun takeItemsGate(): Pair<CountDownLatch, CountDownLatch>? =
        synchronized(gateLock) { pendingItemsGate.also { pendingItemsGate = null } }

    private fun takePostGate(procedure: String): Pair<CountDownLatch, CountDownLatch>? =
        synchronized(gateLock) {
            val pending = pendingPostGate
            if (pending != null && pending.first == procedure) {
                pendingPostGate = null
                pending.second
            } else {
                null
            }
        }

    private fun awaitGate(gate: Pair<CountDownLatch, CountDownLatch>) {
        val (started, release) = gate
        started.countDown()
        if (!release.await(10, TimeUnit.SECONDS)) {
            throw Refusal("Test gate was not released.")
        }
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket = try {
                serverSocket.accept()
            } catch (_: Throwable) {
                return
            }
            workers.execute { serve(socket) }
        }
    }

    private fun serve(socket: Socket) {
        socket.use {
            try {
                it.soTimeout = SOCKET_TIMEOUT_MS
                it.tcpNoDelay = true
                val request = readRequest(it.getInputStream()) ?: return
                val (status, payload) = dispatch(request)
                writeResponse(it.getOutputStream(), status, payload)
            } catch (_: Throwable) {
                // A test may close the store mid-request; the client sees the
                // socket close, which is the realistic cancellation outcome.
            }
        }
    }

    private fun dispatch(request: RecordedRequest): Pair<Int, String> {
        requestLog.add(request)
        try {
            if (request.method == "POST") {
                takePostGate(request.path.removePrefix("/trpc/"))?.let(::awaitGate)
            }
            return route(request)
        } catch (refusal: Refusal) {
            return 500 to errorBody(refusal.message.orEmpty(), request.path)
        } catch (failure: Throwable) {
            return 500 to errorBody(failure.message ?: "Test server failure.", request.path)
        }
    }

    private fun readRequest(input: InputStream): RecordedRequest? {
        val requestLine = readAsciiLine(input) ?: return null
        if (requestLine.isEmpty()) return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null
        val method = parts[0]
        val target = URI(parts[1])

        var contentLength = 0
        while (true) {
            val header = readAsciiLine(input) ?: break
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator > 0) {
                val name = header.substring(0, separator).trim()
                if (name.equals("Content-Length", ignoreCase = true)) {
                    contentLength = header.substring(separator + 1).trim().toIntOrNull() ?: 0
                }
            }
        }

        val body = if (contentLength > 0) {
            val bytes = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val count = input.read(bytes, read, contentLength - read)
                if (count < 0) break
                read += count
            }
            bytes.copyOf(read).decodeToString()
        } else {
            ""
        }

        return RecordedRequest(
            method = method,
            path = target.path.orEmpty(),
            query = parseQuery(target.rawQuery),
            body = body,
        )
    }

    /** Reads one CRLF/LF-terminated ASCII line, bounded by the socket timeout. */
    private fun readAsciiLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val next = input.read()
            if (next < 0) {
                return if (buffer.size() == 0) null else buffer.toByteArray().decodeToString()
            }
            if (next == '\n'.code) break
            if (next != '\r'.code) buffer.write(next)
            if (buffer.size() > MAX_LINE_BYTES) throw Refusal("HTTP header line too long.")
        }
        return buffer.toByteArray().decodeToString()
    }

    private fun writeResponse(output: OutputStream, status: Int, payload: String) {
        val body = payload.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        else -> "Internal Server Error"
    }

    private fun route(request: RecordedRequest): Pair<Int, String> {
        return when ("${request.method} ${request.path}") {
            "GET /trpc/plans.active" -> 200 to success(planOrNullJson())

            "GET /trpc/grocery.itemsForPlan" -> {
                val planId = request.inputJson()?.get("planId")?.jsonPrimitive?.int
                    ?: throw Refusal("Expected planId input.")
                // Snapshot before the gate so a released stale read still
                // carries the payload it was created with.
                val snapshot = items().filter { it.planId == planId }
                takeItemsGate()?.let(::awaitGate)
                200 to success(JsonArray(snapshot.map(::itemJson)))
            }

            "POST /trpc/grocery.setChecked" -> {
                failSetCheckedMessage?.let { throw Refusal(it) }
                val body = request.bodyJson()
                val itemId = body["itemId"]?.jsonPrimitive?.int
                    ?: throw Refusal("Expected itemId.")
                val checked = body["checked"]?.jsonPrimitive?.boolean
                    ?: throw Refusal("Expected checked.")
                val updated = synchronized(lock) {
                    val index = mutableItems.indexOfFirst { it.id == itemId }
                    if (index < 0) {
                        throw Refusal("Grocery item $itemId does not exist.")
                    }
                    mutableItems[index] = mutableItems[index].copy(checked = checked)
                    mutableItems[index]
                }
                200 to success(itemJson(updated))
            }

            "POST /trpc/grocery.addManualItem" -> {
                val body = request.bodyJson()
                val planId = body["planId"]?.jsonPrimitive?.int
                    ?: throw Refusal("Expected planId.")
                val name = body["name"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
                if (name.isEmpty()) throw Refusal("Item name must not be empty.")
                if (plan()?.planId != planId) {
                    throw Refusal("FOREIGN KEY constraint failed: unknown plan.")
                }
                val quantityText = body["quantityText"]
                    ?.takeUnless { it is JsonNull }
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                val section = body["section"]
                    ?.takeUnless { it is JsonNull }
                    ?.jsonPrimitive
                    ?.contentOrNull
                    .orEmpty()
                    .ifEmpty { "other" }
                val added = synchronized(lock) {
                    TestItem(
                        id = nextManualId++,
                        planId = planId,
                        name = name,
                        quantityText = quantityText,
                        section = section,
                        checked = false,
                        isManual = true,
                        sortOrder = 10_000,
                    ).also { mutableItems.add(it) }
                }
                200 to success(itemJson(added))
            }

            "POST /trpc/grocery.removeManualItem" -> {
                val itemId = request.bodyJson()["itemId"]?.jsonPrimitive?.int
                    ?: throw Refusal("Expected itemId.")
                synchronized(lock) {
                    val index = mutableItems.indexOfFirst { it.id == itemId }
                    if (index >= 0) {
                        val item = mutableItems[index]
                        if (!item.isManual || item.checked) {
                            throw Refusal(
                                "Only manually added items can be removed from the list.",
                            )
                        }
                        mutableItems.removeAt(index)
                    }
                }
                200 to success(buildJsonObject { put("ok", true) })
            }

            else -> 404 to errorBody(
                "No procedure found on path \"${request.path.removePrefix("/trpc/")}\"",
                request.path,
            )
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split('&').filter { it.isNotEmpty() }.associate { part ->
            val separator = part.indexOf('=')
            if (separator < 0) {
                URLDecoder.decode(part, "UTF-8") to ""
            } else {
                URLDecoder.decode(part.substring(0, separator), "UTF-8") to
                    URLDecoder.decode(part.substring(separator + 1), "UTF-8")
            }
        }
    }

    private fun planOrNullJson(): JsonElement = plan()?.let { plan ->
        buildJsonObject {
            put("planId", plan.planId)
            put("name", plan.displayName)
            put("displayName", plan.displayName)
            put("status", plan.status)
            put("isFavorite", false)
            put("createdAt", "2026-01-01T00:00:00.000Z")
            put("completedAt", JsonNull)
            put("meals", JsonArray(emptyList()))
        }
    } ?: JsonNull

    private fun itemJson(item: TestItem): JsonObject = buildJsonObject {
        put("id", item.id)
        put("planId", item.planId)
        put("name", item.name)
        put("quantityText", item.quantityText)
        put("section", item.section)
        put("checked", item.checked)
        put("isManual", item.isManual)
        put("sortOrder", item.sortOrder)
    }

    private fun success(data: JsonElement): String = buildJsonObject {
        put("result", buildJsonObject { put("data", data) })
    }.toString()

    private fun errorBody(message: String, path: String): String = buildJsonObject {
        put(
            "error",
            buildJsonObject {
                put("message", message)
                put("code", -32603)
                put(
                    "data",
                    buildJsonObject {
                        put("code", "INTERNAL_SERVER_ERROR")
                        put("httpStatus", 500)
                        put("path", path.removePrefix("/trpc/"))
                    },
                )
            },
        )
    }.toString()

    override fun close() {
        try {
            serverSocket.close()
        } finally {
            workers.shutdownNow()
        }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MS = 5_000
        const val MAX_LINE_BYTES = 8 * 1024
    }
}
