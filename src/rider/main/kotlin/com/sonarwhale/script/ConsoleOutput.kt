package com.sonarwhale.script

import java.util.concurrent.CopyOnWriteArrayList

class ConsoleOutput {
    private val _entries = CopyOnWriteArrayList<ConsoleEntry>()
    val entries: List<ConsoleEntry> get() = _entries.toList()

    fun log(level: LogLevel, message: String, source: String? = null) {
        _entries += ConsoleEntry.LogEntry(now(), level, message, source)
    }

    fun scriptStart(script: ScriptFile) {
        _entries += ConsoleEntry.ScriptBoundary(now(), script.path.toString(), script.phase)
    }

    fun requestStart(method: String, path: String) {
        _entries += ConsoleEntry.RequestBoundary(now(), method, path)
    }

    fun error(script: ScriptFile, e: Throwable, source: String? = null) {
        _entries += ConsoleEntry.ErrorEntry(now(), script.path.toString(),
            e.message ?: e.javaClass.simpleName, source)
    }

    fun http(
        method: String, url: String, status: Int, durationMs: Long,
        requestHeaders: Map<String, String>, requestBody: String?,
        responseHeaders: Map<String, String>, responseBody: String,
        error: String?
    ) {
        _entries += ConsoleEntry.HttpEntry(
            timestampMs = now(),
            method = method,
            url = url,
            status = status,
            durationMs = durationMs,
            responseSize = responseBody.length.toLong(),
            requestHeaders = requestHeaders,
            requestBody = requestBody,
            responseHeaders = responseHeaders,
            responseBody = responseBody,
            error = error
        )
    }

    private fun now() = System.currentTimeMillis()
}
