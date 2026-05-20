package com.sonarwhale.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.script.ConsoleEntry
import com.sonarwhale.script.LogLevel
import com.sonarwhale.script.ScriptPhase
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingUtilities
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.TabSet
import javax.swing.text.TabStop

enum class ConsoleFilter { ALL, LOG, WARN, ERROR, HTTP }

class ConsolePanel : JPanel(BorderLayout()) {

    private val textPane = JTextPane().apply {
        isEditable = false
        background = JBColor.background()
        border = JBUI.Borders.empty(4, 8)
    }
    private val scroll = JBScrollPane(textPane)
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS")
    private val doc get() = textPane.styledDocument

    private var activeFilter = ConsoleFilter.ALL
    private var searchText = ""
    private var allEntries: List<ConsoleEntry> = emptyList()

    private data class HttpEntryTracker(
        val summaryStart: Int,
        val summaryEnd: Int,
        val shell: CollapsibleShell
    )
    private val httpTrackers = mutableListOf<HttpEntryTracker>()

    private inner class CollapsibleShell(val inner: JPanel) : JPanel(BorderLayout()) {
        var expanded = false
            private set

        init { isOpaque = false }

        override fun getPreferredSize(): Dimension {
            val w = (parent?.width ?: textPane.width).coerceAtLeast(200)
            return if (expanded) Dimension(w, super.getPreferredSize().height)
            else Dimension(w, 0)
        }
        override fun getMinimumSize() = preferredSize
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, if (expanded) Int.MAX_VALUE else 0)

        fun toggle() {
            expanded = !expanded
            removeAll()
            if (expanded) add(inner, BorderLayout.CENTER)
            revalidate(); repaint()
            textPane.revalidate(); textPane.repaint()
        }
    }

    init {
        add(scroll, BorderLayout.CENTER)

        textPane.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val pos = textPane.viewToModel2D(e.point).toInt()
                httpTrackers.firstOrNull { pos in it.summaryStart until it.summaryEnd }
                    ?.let { toggleHttpRow(it) }
            }
        })
        textPane.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val pos = textPane.viewToModel2D(e.point).toInt()
                val onSummary = httpTrackers.any { pos in it.summaryStart until it.summaryEnd }
                textPane.cursor = if (onSummary) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                else Cursor.getDefaultCursor()
            }
        })
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun showEntries(entries: List<ConsoleEntry>) {
        allEntries = entries
        renderEntries()
    }

    fun setFilter(filter: ConsoleFilter) {
        activeFilter = filter
        renderEntries()
    }

    fun setSearch(text: String) {
        searchText = text.trim()
        renderEntries()
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private fun renderEntries() {
        val visible = allEntries.filter { matchesFilter(it) && matchesSearch(it) }
        doc.remove(0, doc.length)
        httpTrackers.clear()
        visible.forEach { appendEntry(it) }
        SwingUtilities.invokeLater {
            scroll.verticalScrollBar.let { it.value = it.maximum }
        }
    }

    private fun matchesFilter(entry: ConsoleEntry): Boolean = when (activeFilter) {
        ConsoleFilter.ALL   -> true
        ConsoleFilter.LOG   -> entry is ConsoleEntry.LogEntry
        ConsoleFilter.WARN  -> entry is ConsoleEntry.LogEntry && entry.level == LogLevel.WARN
        ConsoleFilter.ERROR -> (entry is ConsoleEntry.LogEntry && entry.level == LogLevel.ERROR) ||
                entry is ConsoleEntry.ErrorEntry
        ConsoleFilter.HTTP  -> entry is ConsoleEntry.HttpEntry ||
                entry is ConsoleEntry.RequestBoundary
    }

    private fun matchesSearch(entry: ConsoleEntry): Boolean {
        if (searchText.isEmpty()) return true
        val q = searchText.lowercase()
        return when (entry) {
            is ConsoleEntry.LogEntry        -> entry.message.lowercase().contains(q)
            is ConsoleEntry.HttpEntry       -> entry.url.lowercase().contains(q) ||
                    entry.method.lowercase().contains(q)
            is ConsoleEntry.ErrorEntry      -> entry.message.lowercase().contains(q)
            is ConsoleEntry.ScriptBoundary  -> entry.scriptPath.lowercase().contains(q)
            is ConsoleEntry.RequestBoundary -> entry.path.lowercase().contains(q) ||
                    entry.method.lowercase().contains(q)
        }
    }

    private fun appendEntry(entry: ConsoleEntry) = when (entry) {
        is ConsoleEntry.ScriptBoundary  -> appendBoundary(entry)
        is ConsoleEntry.RequestBoundary -> appendRequestBoundary(entry)
        is ConsoleEntry.LogEntry        -> appendLog(entry)
        is ConsoleEntry.ErrorEntry      -> appendError(entry)
        is ConsoleEntry.HttpEntry       -> appendHttp(entry)
    }

    private fun appendBoundary(entry: ConsoleEntry.ScriptBoundary) {
        val phase = if (entry.phase == ScriptPhase.PRE) "pre" else "post"
        val name = entry.scriptPath.substringAfterLast('/').substringAfterLast('\\')
        appendCenteredSeparator("$phase · $name")
    }

    private fun appendRequestBoundary(entry: ConsoleEntry.RequestBoundary) {
        appendCenteredSeparator("request · ${entry.method} ${entry.path}")
    }

    private fun appendCenteredSeparator(text: String) {
        val start = doc.length
        span("$text\n", SwColors.t3, italic = true)
        val centerAttrs = SimpleAttributeSet()
        StyleConstants.setAlignment(centerAttrs, StyleConstants.ALIGN_CENTER)
        doc.setParagraphAttributes(start, doc.length - start, centerAttrs, false)
        // Reset the next paragraph to left so subsequent entries don't inherit centering
        val leftAttrs = SimpleAttributeSet()
        StyleConstants.setAlignment(leftAttrs, StyleConstants.ALIGN_LEFT)
        doc.setParagraphAttributes(doc.length, 1, leftAttrs, false)
    }

    private fun appendLog(entry: ConsoleEntry.LogEntry) {
        resetAlign()
        val msgColor: Color = when (entry.level) {
            LogLevel.LOG     -> SwColors.t1
            LogLevel.WARN    -> SwColors.warn
            LogLevel.ERROR   -> SwColors.delete
            LogLevel.SUCCESS -> SwColors.get
        }
        val time = timeFmt.format(Date(entry.timestampMs))
        span(time, SwColors.t4)
        span("  ${entry.message}", msgColor)
        entry.source?.let { appendSourceRef(it) }
        span("\n", SwColors.t1)
    }

    private fun appendError(entry: ConsoleEntry.ErrorEntry) {
        resetAlign()
        val name = entry.scriptPath.substringAfterLast('/').substringAfterLast('\\')
        val time = timeFmt.format(Date(entry.timestampMs))
        span(time, SwColors.t4)
        span("  $name: ${entry.message}", SwColors.delete)
        entry.source?.let { appendSourceRef(it) }
        span("\n", SwColors.t1)
    }

    private fun appendHttp(entry: ConsoleEntry.HttpEntry) {
        resetAlign()
        val statusColor = statusColor(entry.status)
        val statusText  = if (entry.status == 0) "ERROR" else "${entry.status}"
        val time        = timeFmt.format(Date(entry.timestampMs))

        val summaryStart = doc.length
        span("▶ ", SwColors.t3)
        span(time, SwColors.t4)
        span("  ", SwColors.t4)
        span(entry.method, methodColor(entry.method), bold = true)
        span(" ${entry.url}", SwColors.t2)
        span("  ·  ", SwColors.t3)
        span(statusText, statusColor, bold = true)
        span("  ·  ${entry.durationMs}ms  ·  ${formatSize(entry.responseSize)}", SwColors.t3)
        val summaryEnd = doc.length
        span("\n", SwColors.t1)

        val details = buildHttpDetails(entry)
        val shell = CollapsibleShell(details)
        httpTrackers.add(HttpEntryTracker(summaryStart, summaryEnd, shell))

        textPane.caretPosition = doc.length
        textPane.insertComponent(shell)
        doc.insertString(doc.length, "\n", null)
    }

    private fun toggleHttpRow(tracker: HttpEntryTracker) {
        tracker.shell.toggle()
        // Patch the ▶/▼ glyph in-place (both are 2 chars, document offsets stay stable)
        val glyph = if (tracker.shell.expanded) "▼ " else "▶ "
        doc.remove(tracker.summaryStart, 2)
        doc.insertString(tracker.summaryStart, glyph, attrs(SwColors.t3))
    }

    private fun buildHttpDetails(entry: ConsoleEntry.HttpEntry): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyLeft(JBUI.scale(16))
            isOpaque = false
        }

        fun section(title: String, content: String) {
            if (content.isBlank()) return
            panel.add(JBLabel(title).apply {
                font = JBUI.Fonts.label().deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                foreground = SwColors.t3
                border = JBUI.Borders.emptyTop(JBUI.scale(4))
                alignmentX = LEFT_ALIGNMENT
            })
            panel.add(javax.swing.JTextArea(content).apply {
                isEditable = false
                font = monoFont()
                lineWrap = true
                wrapStyleWord = false
                border = JBUI.Borders.empty(2, 4)
                background = JBColor.background()
                foreground = SwColors.t2
                alignmentX = LEFT_ALIGNMENT
            })
        }

        val reqHeadersText = entry.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        section("REQUEST HEADERS", reqHeadersText)
        if (entry.requestBody != null) section("REQUEST BODY", entry.requestBody)

        if (entry.error != null) {
            section("ERROR", entry.error)
        } else {
            val respHeadersText = entry.responseHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            section("RESPONSE HEADERS", respHeadersText)
            if (entry.responseBody.isNotBlank()) section("RESPONSE BODY", entry.responseBody.take(2000))
        }

        return panel
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resetAlign() {
        val attrs = SimpleAttributeSet()
        StyleConstants.setAlignment(attrs, StyleConstants.ALIGN_LEFT)
        doc.setParagraphAttributes(doc.length, 1, attrs, false)
    }

    private fun span(text: String, fg: Color, bold: Boolean = false, italic: Boolean = false) {
        doc.insertString(doc.length, text, attrs(fg, bold = bold, italic = italic))
    }

    private fun attrs(fg: Color, bold: Boolean = false, italic: Boolean = false): SimpleAttributeSet =
        SimpleAttributeSet().also {
            StyleConstants.setForeground(it, fg)
            StyleConstants.setFontFamily(it, "JetBrains Mono")
            StyleConstants.setFontSize(it, JBUI.scale(11))
            StyleConstants.setBold(it, bold)
            StyleConstants.setItalic(it, italic)
        }

    private fun appendSourceRef(source: String) {
        val panelWidth = (textPane.parent?.width ?: textPane.width)
            .takeIf { it > 0 } ?: JBUI.scale(500)
        val tabPos = (panelWidth - JBUI.scale(16)).toFloat()
        val tabSet = TabSet(arrayOf(TabStop(tabPos, TabStop.ALIGN_RIGHT, TabStop.LEAD_NONE)))
        val paraAttrs = SimpleAttributeSet()
        StyleConstants.setTabSet(paraAttrs, tabSet)
        val allText = runCatching { doc.getText(0, doc.length) }.getOrDefault("")
        val lineStart = allText.lastIndexOf('\n').let { if (it < 0) 0 else it + 1 }
        doc.setParagraphAttributes(lineStart, (doc.length - lineStart).coerceAtLeast(1), paraAttrs, false)
        span("\t$source", SwColors.t3)
    }

    private fun methodColor(method: String): Color = when (method.uppercase()) {
        "GET"    -> SwColors.get
        "POST"   -> SwColors.post
        "PUT"    -> SwColors.put
        "DELETE" -> SwColors.delete
        "PATCH"  -> SwColors.patch
        else     -> SwColors.t2
    }

    private fun statusColor(status: Int): Color = when {
        status == 0        -> SwColors.delete
        status in 200..299 -> SwColors.get
        status in 300..399 -> SwColors.put
        status in 400..499 -> SwColors.warn
        else               -> SwColors.delete
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024         -> "$bytes B"
        bytes < 1024L * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        else                 -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }

    private fun monoFont(): Font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(11))
}
