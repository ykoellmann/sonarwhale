package com.sonarwhale.toolwindow

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.intellij.ide.scratch.ScratchRootType
import com.intellij.lang.Language
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.script.ConsoleEntry
import com.sonarwhale.script.TestResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLayeredPane
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTextArea
import javax.swing.SwingWorker

class ResponsePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val statusLabel = JBLabel("Response").apply {
        font = font.deriveFont(Font.BOLD, 12f)
        foreground = JBColor.GRAY
    }
    private val timeLabel = JBLabel("").apply {
        font = font.deriveFont(11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 12, 0, 0)
    }
    private val sizeLabel = JBLabel("").apply {
        font = font.deriveFont(11f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 8, 0, 0)
    }
    private val openButton = JButton("Open in Editor").apply {
        font = font.deriveFont(10f)
    }
    private val clearConsoleBtn = JButton("Clear").apply {
        font = font.deriveFont(11f)
        toolTipText = "Clear console output"
    }
    private val tabActionsBar = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
        isOpaque = false
    }

    private var currentContentType = ""
    private val bodyArea = JTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        border = JBUI.Borders.empty(8)
    }
    private val bodyScroll = JBScrollPane(bodyArea)
    private val tabs = CollapsibleTabPane()
    private val testsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }
    private val testsScroll = JBScrollPane(testsPanel)
    private val consolePanel = ConsolePanel()

    var autoFormatResponse: Boolean = true

    init {
        statusLabel.alignmentY = 0.5f
        timeLabel.alignmentY = 0.5f
        sizeLabel.alignmentY = 0.5f

        val contentRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = 0.0f
        }
        contentRow.add(statusLabel)
        contentRow.add(timeLabel)
        contentRow.add(sizeLabel)

        val headerBar = object : JPanel() {
            override fun getPreferredSize() = Dimension(super.getPreferredSize().width, JBUI.scale(42))
            override fun getMinimumSize()   = Dimension(0, JBUI.scale(42))
            override fun getMaximumSize()   = Dimension(Int.MAX_VALUE, JBUI.scale(42))
        }.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 8)
        }
        headerBar.add(Box.createVerticalGlue())
        headerBar.add(contentRow)
        headerBar.add(Box.createVerticalGlue())

        val headerWrapper = JPanel(BorderLayout())
        headerWrapper.add(headerBar, BorderLayout.CENTER)
        headerWrapper.add(JSeparator(), BorderLayout.SOUTH)

        add(headerWrapper, BorderLayout.NORTH)
        tabs.addTab("Body", bodyScroll)
        tabs.addTab("Tests", testsScroll)
        tabs.addTab("Console", consolePanel)
        add(buildTabsWithActions(), BorderLayout.CENTER)

        openButton.addActionListener { openInEditor() }
        clearConsoleBtn.addActionListener {
            consolePanel.showEntries(emptyList())
            tabs.setTitleAt(tabs.indexOfComponent(consolePanel), "Console")
            updateTabActions()
        }
        tabs.onTabChanged = { _, _ -> updateTabActions() }
    }

    fun showResponse(statusCode: Int, body: String, durationMs: Long, contentType: String = "") {
        currentContentType = contentType
        if (statusCode == 0) {
            statusLabel.text = "Error"
            statusLabel.foreground = JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
            timeLabel.text = ""
            sizeLabel.text = ""
            bodyArea.text = body
            bodyArea.caretPosition = 0
            updateTabActions()
            return
        }

        val phrase = httpStatusText(statusCode)
        statusLabel.text = if (phrase.isNotEmpty()) "$statusCode $phrase" else "$statusCode"
        statusLabel.foreground = statusColor(statusCode)
        timeLabel.text = "${durationMs} ms"
        sizeLabel.text = formatByteSize(body.toByteArray().size)
        bodyArea.text = "…"
        updateTabActions()

        object : SwingWorker<String, Unit>() {
            override fun doInBackground(): String = formatBody(body, contentType)
            override fun done() {
                val text = runCatching { get() }.getOrDefault(body)
                bodyArea.text = text
                bodyArea.caretPosition = 0
                if (text.isNotEmpty()) {
                    val (_, ext) = ContentTypeUtils.langAndExt(currentContentType)
                    openButton.toolTipText = "Open in editor as .$ext"
                }
                updateTabActions()
            }
        }.execute()
    }

    fun clear() {
        statusLabel.text = "Response"
        statusLabel.foreground = JBColor.GRAY
        timeLabel.text = ""
        sizeLabel.text = ""
        bodyArea.text = ""
        updateTabActions()
        currentContentType = ""
        testsPanel.removeAll()
        tabs.setTitleAt(tabs.indexOfComponent(testsScroll), "Tests")
        consolePanel.showEntries(emptyList())
        tabs.setTitleAt(tabs.indexOfComponent(consolePanel), "Console")
        testsPanel.revalidate()
    }

    fun showTestResults(results: List<TestResult>) {
        testsPanel.removeAll()
        val testsIdx = tabs.indexOfComponent(testsScroll)
        if (results.isEmpty()) {
            tabs.setTitleAt(testsIdx, "Tests")
            testsPanel.revalidate()
            testsPanel.repaint()
            return
        }
        val passed = results.count { it.passed }
        tabs.setTitleAt(testsIdx, "Tests ($passed/${results.size})")

        for (result in results) {
            val row = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
                isOpaque = false
            }
            val icon = JBLabel(if (result.passed) "✓" else "✗").apply {
                foreground = if (result.passed)
                    JBColor(java.awt.Color(0x00, 0xAA, 0x55), java.awt.Color(0x44, 0xCC, 0x77))
                else
                    JBColor(java.awt.Color(0xCC, 0x00, 0x00), java.awt.Color(0xFF, 0x44, 0x44))
                font = font.deriveFont(Font.BOLD, 12f)
            }
            val name = JBLabel(result.name).apply { font = font.deriveFont(12f) }
            row.add(icon)
            row.add(name)
            if (!result.passed && result.error != null) {
                row.add(JBLabel("  ${result.error}").apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(Font.ITALIC, 11f)
                })
            }
            testsPanel.add(row)
        }

        testsPanel.revalidate()
        testsPanel.repaint()

        if (results.any { !it.passed }) {
            tabs.selectedIndex = tabs.indexOfComponent(testsScroll)
        }
    }

    fun showConsole(entries: List<ConsoleEntry>) {
        val consoleIdx = tabs.indexOfComponent(consolePanel)
        tabs.setTitleAt(consoleIdx, if (entries.isEmpty()) "Console" else "Console (${entries.size})")
        consolePanel.showEntries(entries)
        if (entries.isNotEmpty()) {
            tabs.selectedIndex = consoleIdx
        }
    }

    private fun buildTabsWithActions(): JLayeredPane {
        tabActionsBar.add(openButton)
        tabActionsBar.add(clearConsoleBtn)
        updateTabActions()
        return object : JLayeredPane() {
            override fun doLayout() {
                tabs.setBounds(0, 0, width, height)
                val bPref = tabActionsBar.preferredSize
                tabActionsBar.setBounds(width - bPref.width - 4, 0, bPref.width, bPref.height)
            }
            override fun getPreferredSize(): Dimension = tabs.preferredSize
        }.also { lp ->
            lp.add(tabs, JLayeredPane.DEFAULT_LAYER, -1)
            lp.add(tabActionsBar, JLayeredPane.PALETTE_LAYER, -1)
        }
    }

    private fun updateTabActions() {
        val title = if (tabs.selectedIndex >= 0) tabs.getTitleAt(tabs.selectedIndex) else ""
        openButton.isVisible = title.startsWith("Body") && bodyArea.text.isNotEmpty()
        clearConsoleBtn.isVisible = title.startsWith("Console")
        tabActionsBar.revalidate()
        tabActionsBar.repaint()
    }

    private fun openInEditor() {
        val content = bodyArea.text.ifEmpty { return }
        val (langId, ext) = ContentTypeUtils.langAndExt(currentContentType)
        val lang = langId.takeIf { it.isNotEmpty() }?.let { Language.findLanguageByID(it) }
            ?: PlainTextLanguage.INSTANCE
        val scratch = ScratchRootType.getInstance()
            .createScratchFile(project, "sonarwhale-response.$ext", lang, content)
            ?: return
        FileEditorManager.getInstance(project).openFile(scratch, true)
    }

    private fun statusColor(code: Int): Color = when {
        code in 200..299 -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x44, 0xCC, 0x77))
        code in 300..399 -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xAA, 0x33))
        code in 400..499 -> JBColor(Color(0xCC, 0x44, 0x00), Color(0xFF, 0x66, 0x33))
        code >= 500      -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        else             -> JBColor.GRAY
    }

    private fun formatBody(text: String, contentType: String): String {
        if (!autoFormatResponse) return text
        val ct = contentType.lowercase()
        val trimmed = text.trim()
        return when {
            "json" in ct || (ct.isEmpty() && (trimmed.startsWith("{") || trimmed.startsWith("[")))
                -> runCatching { prettyGson.toJson(JsonParser.parseString(trimmed)) }.getOrDefault(text)
            "xml" in ct || "html" in ct
                -> runCatching { formatXml(trimmed) }.getOrDefault(text)
            else -> text
        }
    }

    private fun formatXml(xml: String): String {
        val transformer = xmlTransformerFactory.newTransformer().apply {
            setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
            setOutputProperty(javax.xml.transform.OutputKeys.OMIT_XML_DECLARATION, "yes")
        }
        val result = java.io.StringWriter()
        transformer.transform(
            javax.xml.transform.stream.StreamSource(java.io.StringReader(xml)),
            javax.xml.transform.stream.StreamResult(result)
        )
        return result.toString().trim()
    }

    companion object {
        private val prettyGson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
        private val xmlTransformerFactory = javax.xml.transform.TransformerFactory.newInstance()

        private fun httpStatusText(code: Int): String = when (code) {
            200 -> "OK";          201 -> "Created";            202 -> "Accepted"
            204 -> "No Content";  206 -> "Partial Content"
            301 -> "Moved Permanently"; 302 -> "Found";        304 -> "Not Modified"
            400 -> "Bad Request"; 401 -> "Unauthorized";       403 -> "Forbidden"
            404 -> "Not Found";   405 -> "Method Not Allowed"; 408 -> "Request Timeout"
            409 -> "Conflict";    410 -> "Gone";               422 -> "Unprocessable Entity"
            429 -> "Too Many Requests"
            500 -> "Internal Server Error"; 502 -> "Bad Gateway"
            503 -> "Service Unavailable";   504 -> "Gateway Timeout"
            else -> ""
        }

        private fun formatByteSize(bytes: Int): String = when {
            bytes < 1024        -> "$bytes B"
            bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
            else                -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
        }
    }
}
