package com.sonarwhale.model

import com.sonarwhale.script.ConsoleEntry
import com.sonarwhale.script.LogLevel
import com.sonarwhale.script.TestResult
import java.util.UUID

data class RequestRunEntry(
    val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val collectionId: String,
    val endpointId: String,
    val method: String,
    val url: String,
    val statusCode: Int,
    val responseBody: String,
    val durationMs: Long,
    val consoleEntries: List<StoredConsoleEntry> = emptyList(),
    val testResults: List<TestResult> = emptyList(),
    val environmentName: String? = null
) {
    companion object {
        const val MAX_BODY_CHARS = 50_000
    }
}

data class StoredConsoleEntry(
    val type: String,
    val timestampMs: Long,
    val message: String,
    val level: String? = null
)

fun ConsoleEntry.toStored(): StoredConsoleEntry = when (this) {
    is ConsoleEntry.LogEntry      -> StoredConsoleEntry("log",      timestampMs, message, level.name)
    is ConsoleEntry.ErrorEntry    -> StoredConsoleEntry("error",    timestampMs, message, "ERROR")
    is ConsoleEntry.HttpEntry     -> StoredConsoleEntry("http",     timestampMs, "$method $url → $status", null)
    is ConsoleEntry.ScriptBoundary  -> StoredConsoleEntry("boundary", timestampMs, "[${phase.name.lowercase()}] $scriptPath", null)
    is ConsoleEntry.RequestBoundary -> StoredConsoleEntry("boundary", timestampMs, "$method $path", null)
}

fun StoredConsoleEntry.toConsoleEntry(): ConsoleEntry = when (type) {
    "error" -> ConsoleEntry.ErrorEntry(timestampMs, "", message)
    else    -> ConsoleEntry.LogEntry(
        timestampMs,
        runCatching { LogLevel.valueOf(level ?: "LOG") }.getOrDefault(LogLevel.LOG),
        message
    )
}
