package dev.rusty.app

import dev.rusty.app.renderer.HttpRequest
import dev.rusty.app.renderer.HttpResponse
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Seam between HTTP and the live app; the service hands the server one. All calls may run
 * on a pool thread — implementations are responsible for their own thread-safety, mirroring
 * how [dev.rusty.app.renderer.RendererRuntime] is the seam for the MediaRenderer endpoint.
 */
interface ControlRuntime {
    fun snapshot(): ControlSnapshot

    /** Applies atomically; returns the RESULTING snapshot (never echoes the request). */
    fun setScreen(on: Boolean, brightness: Int?): ControlSnapshot

    /** Returns resulting snapshot, or null when volume is fixed (router answers 409). */
    fun setVolume(percent: Int): ControlSnapshot?

    fun filters(): ImmichFilters
    fun setFilters(f: ImmichFilters)

    /** kind: "albums" | "people" | "tags". */
    fun immichList(kind: String): ControlImmichResult

    fun controlPageHtml(): String

    /** May block on a (TTL-cached) GitHub fetch — pool threads only, like [immichList]. */
    fun updateCheck(): ControlUpdateCheck

    /** Kicks off the async APK download+install; returns immediately with the outcome class. */
    fun startUpdateInstall(): ControlInstallStart
}

sealed class ControlImmichResult {
    data class Ok(val items: List<ImmichPickerItem>) : ControlImmichResult()
    object NotConfigured : ControlImmichResult()
    object Unauthorized : ControlImmichResult()
    object Unreachable : ControlImmichResult()
}

/**
 * Pure request router for the remote-control HTTP API: parses nothing itself (that's
 * [dev.rusty.app.renderer.RendererHttpProtocol.parseRequest], shared with the DLNA endpoint),
 * just maps an already-parsed [HttpRequest] to an [HttpResponse] against a [ControlRuntime].
 *
 * No `android.*` imports anywhere in this file — that is what makes the whole API surface
 * unit-testable off-device (this project has no emulator). Every Android touchpoint
 * (AudioManager, prefs, the receiver store) lives behind [ControlRuntime], supplied by the
 * foreground service.
 *
 * Security posture baked in here (see design doc, not optional):
 *  - Host header is validated against the device's own addresses to kill DNS-rebinding attacks
 *    from a page loaded over the public internet; checked FIRST, before routing, so even an
 *    unknown path from a rebound host gets 403 rather than 404.
 *  - No CORS headers are ever emitted. The control page is same-origin, so it needs none; a
 *    cross-origin fetch() from another site is refused by the browser with no header to allow it.
 *  - Writes require `Content-Type: application/json`, which a plain cross-origin `<form>` POST
 *    cannot set without triggering a CORS preflight (which we also don't answer).
 *  - Request bodies are capped well below the renderer's parser cap so a malicious client can't
 *    tie up a worker thread building a huge JSONObject.
 *
 * Handlers never throw into the server loop: JSON parsing is try/catch'd to 400, and anything
 * unexpected — deliberately [Throwable], not [Exception]: org.json's tokenizer is recursive
 * descent, so a small-but-deeply-nested body can raise [StackOverflowError] rather than a
 * catchable [Exception], and an uncaught [Throwable] on any thread kills the Android process —
 * is caught at the top of [route] and turned into a 500 JSON error.
 */
object ControlProtocol {
    const val MAX_API_BODY_BYTES = 16 * 1024

    private const val JSON_CONTENT_TYPE = "application/json"
    private const val HTML_CONTENT_TYPE = "text/html; charset=utf-8"
    private val IMMICH_KINDS = setOf("albums", "people", "tags")

    /** Diagnostic sink for failures caught at the [route] boundary. Defaults to a no-op: this
     *  file must stay `android.*`-free, so it cannot call `Log` itself, and [route]'s signature
     *  is fixed (Tasks 6/8/9 call it verbatim) so the sink can't be threaded through as a
     *  parameter either. The owning service (Task 9) can assign a real logger here at startup;
     *  tests can assign a recording lambda to assert a failure was observed instead of it
     *  vanishing silently. Invoked best-effort — see [route]: a throwing sink must never turn a
     *  handled 500 into an escaping [Throwable], so any failure from the sink itself is swallowed
     *  rather than reported (there is nowhere left to report it to).
     *
     *  `@Volatile` because it is written on the main thread ([ControlService.onCreate]) and read
     *  from pool threads: without it, a worker could keep observing the no-op default and a
     *  handled 500 would leave no trace in logcat at all — precisely when one is most wanted. */
    @Volatile
    var onInternalError: (Throwable, HttpRequest) -> Unit = { _, _ -> }

    /** localHosts: the device's own addresses; Host header must match one (port optional) or the
     *  request is 403 (DNS-rebinding guard). */
    fun route(req: HttpRequest, rt: ControlRuntime, localHosts: Set<String>): HttpResponse {
        if (!hostAllowed(req, localHosts)) return errorResponse(403, "Forbidden", "host not allowed")

        return try {
            dispatch(req, rt)
        } catch (t: Throwable) {
            // Best-effort: a caller-supplied sink that itself throws (OOM in a logger, a Log
            // call that throws, ...) must not be able to convert this handled 500 into an
            // uncaught Throwable escaping route() — that would reinstate the exact
            // process-killing failure mode this catch exists to close.
            try {
                onInternalError(t, req)
            } catch (sinkFailure: Throwable) {
                // Nowhere left to report this — deliberately swallowed.
            }
            errorResponse(500, "Internal Server Error", "internal error")
        }
    }

    private fun hostAllowed(req: HttpRequest, localHosts: Set<String>): Boolean {
        val host = req.headers["HOST"] ?: return false
        val hostOnly = host.substringBefore(':').trim().lowercase()
        if (hostOnly.isEmpty()) return false
        return localHosts.any { it.lowercase() == hostOnly }
    }

    private fun dispatch(req: HttpRequest, rt: ControlRuntime): HttpResponse {
        val path = req.path
        return when {
            req.method == "GET" && path == "/api/state" ->
                jsonOk(rt.snapshot().toJson())

            req.method == "POST" && path == "/api/screen" ->
                writeGuarded(req) { handleSetScreen(req, rt) }

            req.method == "POST" && path == "/api/volume" ->
                writeGuarded(req) { handleSetVolume(req, rt) }

            req.method == "GET" && path == "/api/slideshow/filters" ->
                jsonOk(filtersJson(rt.filters()))

            req.method == "PUT" && path == "/api/slideshow/filters" ->
                writeGuarded(req) { handleSetFilters(req, rt) }

            req.method == "GET" && path.startsWith("/api/immich/") -> {
                // startsWith is only a fast filter; the kind must exactly match the whitelist
                // (mirroring RendererHttpProtocol's enum-lookup pattern) so an unknown segment —
                // or a path-traversal payload smuggled into it — 404s instead of reaching the
                // runtime, which will build authenticated upstream Immich requests from it.
                val kind = path.removePrefix("/api/immich/")
                if (kind in IMMICH_KINDS) handleImmichList(kind, rt) else errorResponse(404, "Not Found", "not found")
            }

            req.method == "GET" && path == "/api/update" ->
                jsonOk(rt.updateCheck().toJson())

            req.method == "POST" && path == "/api/update/install" ->
                writeGuarded(req) { handleUpdateInstall(rt) }

            req.method == "GET" && path == "/" ->
                HttpResponse(200, "OK", listOf("Content-Type" to HTML_CONTENT_TYPE), rt.controlPageHtml())

            else -> errorResponse(404, "Not Found", "not found")
        }
    }

    // -------------------------------------------------------------------
    // Write-route guards (body size, then content type)
    // -------------------------------------------------------------------

    /** Body-size cap checked before the content-type check, so an oversized body is reported as
     *  oversized (413) rather than as an unsupported media type. */
    private inline fun writeGuarded(req: HttpRequest, handler: () -> HttpResponse): HttpResponse {
        if (req.body.toByteArray(Charsets.UTF_8).size > MAX_API_BODY_BYTES) {
            return errorResponse(413, "Payload Too Large", "body too large")
        }
        if (!isJsonContentType(req.headers["CONTENT-TYPE"])) {
            return errorResponse(415, "Unsupported Media Type", "Content-Type must be application/json")
        }
        return handler()
    }

    /** Matches `application/json` case-insensitively, ignoring trailing parameters
     *  (e.g. `application/json; charset=utf-8`). */
    private fun isJsonContentType(headerValue: String?): Boolean {
        val mediaType = headerValue?.substringBefore(';')?.trim() ?: return false
        return mediaType.equals(JSON_CONTENT_TYPE, ignoreCase = true)
    }

    // -------------------------------------------------------------------
    // /api/screen
    // -------------------------------------------------------------------

    private fun handleSetScreen(req: HttpRequest, rt: ControlRuntime): HttpResponse {
        val obj = parseJsonObject(req.body) ?: return errorResponse(400, "Bad Request", "malformed JSON")

        val onValue = obj.opt("on")
        if (onValue !is Boolean) return errorResponse(400, "Bad Request", "'on' must be a boolean")

        val brightness: Int? = when {
            !obj.has("brightness") || obj.isNull("brightness") -> null
            else -> {
                val raw = obj.opt("brightness")
                val asInt = (raw as? Number)?.toInt()
                if (raw !is Number || asInt == null || asInt.toDouble() != raw.toDouble()) {
                    return errorResponse(400, "Bad Request", "'brightness' must be an integer")
                }
                if (asInt < 1 || asInt > 100) return errorResponse(400, "Bad Request", "'brightness' must be 1..100")
                asInt
            }
        }

        val result = rt.setScreen(onValue, brightness)
        return jsonOk(result.toJson())
    }

    // -------------------------------------------------------------------
    // /api/volume
    // -------------------------------------------------------------------

    private fun handleSetVolume(req: HttpRequest, rt: ControlRuntime): HttpResponse {
        val obj = parseJsonObject(req.body) ?: return errorResponse(400, "Bad Request", "malformed JSON")

        val raw = obj.opt("value")
        val percent = (raw as? Number)?.toInt()
        if (raw !is Number || percent == null || percent.toDouble() != raw.toDouble()) {
            return errorResponse(400, "Bad Request", "'value' must be an integer")
        }
        if (percent < 0 || percent > 100) return errorResponse(400, "Bad Request", "'value' must be 0..100")

        val result = rt.setVolume(percent) ?: return errorResponse(409, "Conflict", "volume is fixed")
        return jsonOk(result.toJson())
    }

    // -------------------------------------------------------------------
    // /api/slideshow/filters
    // -------------------------------------------------------------------

    private fun handleSetFilters(req: HttpRequest, rt: ControlRuntime): HttpResponse {
        val filters = ControlFilters.parse(req.body) ?: return errorResponse(400, "Bad Request", "malformed filters")
        rt.setFilters(filters)
        return jsonOk(filtersJson(filters))
    }

    private fun filtersJson(f: ImmichFilters): String {
        val o = JSONObject()
        o.put("albumIds", JSONArray(f.albumIds))
        o.put("personIds", JSONArray(f.personIds))
        o.put("tagIds", JSONArray(f.tagIds))
        return o.toString()
    }

    // -------------------------------------------------------------------
    // /api/immich/{albums,people,tags}
    // -------------------------------------------------------------------

    private fun handleImmichList(kind: String, rt: ControlRuntime): HttpResponse =
        when (val result = rt.immichList(kind)) {
            is ControlImmichResult.Ok -> {
                val arr = JSONArray()
                for (item in result.items) {
                    val o = JSONObject()
                    o.put("id", item.id)
                    o.put("name", item.label)
                    arr.put(o)
                }
                jsonOk(arr.toString())
            }
            ControlImmichResult.NotConfigured -> errorResponse(404, "Not Found", "immich not configured")
            ControlImmichResult.Unauthorized -> errorResponse(502, "Bad Gateway", "immich unauthorized")
            ControlImmichResult.Unreachable -> errorResponse(502, "Bad Gateway", "immich unreachable")
        }

    // -------------------------------------------------------------------
    // /api/update/install
    // -------------------------------------------------------------------

    /** The request body is intentionally unread: an install request carries no parameters,
     *  but the standard write guards (size cap, JSON content type) still apply so a plain
     *  cross-origin `<form>` POST can't trigger the device's install prompt. */
    private fun handleUpdateInstall(rt: ControlRuntime): HttpResponse =
        when (rt.startUpdateInstall()) {
            ControlInstallStart.STARTED ->
                HttpResponse(202, "Accepted", listOf("Content-Type" to JSON_CONTENT_TYPE), """{"status":"started"}""")
            ControlInstallStart.NO_UPDATE -> errorResponse(409, "Conflict", "no update available")
            ControlInstallStart.BUSY -> errorResponse(409, "Conflict", "install already in progress")
            ControlInstallStart.NO_APK -> errorResponse(503, "Service Unavailable", "release has no APK asset")
        }

    // -------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------

    private fun parseJsonObject(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (e: JSONException) {
        null
    }

    private fun jsonOk(body: String): HttpResponse =
        HttpResponse(200, "OK", listOf("Content-Type" to JSON_CONTENT_TYPE), body)

    private fun errorResponse(status: Int, reason: String, message: String): HttpResponse =
        HttpResponse(status, reason, listOf("Content-Type" to JSON_CONTENT_TYPE), JSONObject().put("error", message).toString())
}
