package com.routex.gutter

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.routex.RouteXStateService
import com.routex.model.ApiEndpoint
import com.routex.model.HttpMethod
import com.routex.service.RouteIndexService
import javax.swing.Icon

/**
 * Adds play-button gutter icons to open C# editors at lines where an HTTP endpoint
 * attribute or minimal-API mapping is detected.
 *
 * Matching strategy (no PSI dependency):
 *  1. For each line in a .cs file look for HTTP-method markers:
 *     - Attribute style:  [HttpGet(...)], [HttpPost(...)], ...
 *     - Minimal API:      app.MapGet(...), app.MapPost(...), ...
 *  2. Extract the string literal on the same line (the route template).
 *  3. Match against loaded OpenAPI endpoints by method AND path suffix/contains.
 */
@Service(Service.Level.PROJECT)
class RouteXGutterService(private val project: Project) : Disposable {

    private val ROUTEX_MARKER = com.intellij.openapi.util.Key.create<Boolean>("routex.gutter")

    private var currentEndpoints: List<ApiEndpoint> = emptyList()

    // Precompiled patterns per HTTP method
    private data class MethodPattern(
        val method: HttpMethod,
        val attrPrefix: String,   // e.g. "httpget"
        val mapPrefix: String     // e.g. ".mapget("
    )

    private val METHOD_PATTERNS = HttpMethod.entries.map { m ->
        val name = m.name.lowercase()
        MethodPattern(
            method    = m,
            attrPrefix = "http${name}",
            mapPrefix  = ".map${name}("
        )
    }

    // Regex to extract the first string literal from a line
    private val STRING_LITERAL = Regex(""""([^"]*?)"""")

    init {
        RouteIndexService.getInstance(project).addListener { endpoints ->
            currentEndpoints = endpoints
            refreshAllOpenEditors()
        }

        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                if (event.editor.project == project) markEditor(event.editor)
            }
        }, this)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Force re-mark all currently open editors (e.g. after endpoint list changes). */
    fun refreshAllOpenEditors() {
        ApplicationManager.getApplication().invokeLater {
            EditorFactory.getInstance().allEditors
                .filter { it.project == project }
                .forEach { markEditor(it) }
        }
    }

    // ── Core logic ────────────────────────────────────────────────────────────

    private fun markEditor(editor: Editor) {
        val document = editor.document
        val vFile = FileDocumentManager.getInstance().getFile(document) ?: return
        if (vFile.extension?.lowercase() != "cs") return

        val markup = editor.markupModel

        // Clear old markers
        markup.allHighlighters
            .filter { it.getUserData(ROUTEX_MARKER) == true }
            .forEach { markup.removeHighlighter(it) }

        if (currentEndpoints.isEmpty()) return

        val lineCount = document.lineCount
        var line = 0
        while (line < lineCount) {
            val lineText = lineText(document, line)
            val lower    = lineText.lowercase()

            val isAttr    = METHOD_PATTERNS.any { lower.contains(it.attrPrefix) }
            val isMapCall = METHOD_PATTERNS.any { lower.contains(it.mapPrefix) }

            if (!isAttr && !isMapCall) { line++; continue }

            val endpoint = findEndpointForLine(lineText)
            if (endpoint == null) { line++; continue }

            // For attribute style [HttpXxx] → find the next method-declaration line.
            // For minimal-API .MapXxx(  → icon stays on the same line.
            val iconLine = if (isAttr) findMethodDeclarationLine(document, line + 1, lineCount)
                           else line

            val lineStart = document.getLineStartOffset(iconLine)
            val lineEnd   = document.getLineEndOffset(iconLine)

            val highlighter = markup.addRangeHighlighter(
                lineStart, lineEnd,
                HighlighterLayer.ADDITIONAL_SYNTAX,
                null,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            highlighter.putUserData(ROUTEX_MARKER, true)
            highlighter.gutterIconRenderer = RouteXGutterRenderer(endpoint, project)

            line++
        }
    }

    /**
     * Starting from [startLine], returns the first line that looks like a method/function
     * declaration (has an access modifier or return-type keyword), skipping blank lines
     * and additional attribute lines. Falls back to [startLine] if nothing found within
     * MAX_LOOKAHEAD lines.
     */
    private fun findMethodDeclarationLine(document: com.intellij.openapi.editor.Document,
                                          startLine: Int, lineCount: Int): Int {
        val MAX_LOOKAHEAD = 6
        for (i in startLine until minOf(startLine + MAX_LOOKAHEAD, lineCount)) {
            val text  = lineText(document, i).trim()
            val lower = text.lowercase()
            if (text.isEmpty()) continue              // blank — keep scanning
            if (text.startsWith("[")) continue        // another attribute
            // Method declaration heuristics
            if (lower.contains("public ")   || lower.contains("private ") ||
                lower.contains("protected ") || lower.contains("internal ") ||
                lower.contains("static ")   || lower.contains("async ") ||
                lower.contains("override ") || lower.contains("virtual ")) {
                return i
            }
            break  // non-blank, non-attribute, no modifier → stop looking
        }
        return startLine
    }

    private fun lineText(document: com.intellij.openapi.editor.Document, line: Int): String {
        val start = document.getLineStartOffset(line)
        val end   = document.getLineEndOffset(line)
        return document.getText(com.intellij.openapi.util.TextRange(start, end))
    }

    /**
     * Returns the best-matching endpoint for a given source line, or null if the
     * line doesn't look like an HTTP mapping.
     *
     * Matching priority:
     *  1. Method matches AND path literal is an exact suffix of the endpoint path
     *  2. Method matches AND path literal appears anywhere in the endpoint path
     *  3. Method matches AND endpoint path appears in the path literal (catch-all attributes)
     */
    private fun findEndpointForLine(lineText: String): ApiEndpoint? {
        val lower = lineText.lowercase()

        for (pat in METHOD_PATTERNS) {
            if (!lower.contains(pat.attrPrefix) && !lower.contains(pat.mapPrefix)) continue

            // Extract first string literal on the line (the route template)
            val rawLiteral = STRING_LITERAL.find(lineText)?.groupValues?.get(1)
            val literal    = rawLiteral?.trimStart('/')?.lowercase()

            val candidates = currentEndpoints.filter { it.method == pat.method }
            if (candidates.isEmpty()) continue

            if (literal != null && literal.isNotEmpty()) {
                // Priority 1: endpoint path ends with the literal (handles controller prefix case)
                candidates.firstOrNull { ep ->
                    ep.path.trimStart('/').lowercase().endsWith(literal)
                }?.let { return it }

                // Priority 2: endpoint path contains the literal
                candidates.firstOrNull { ep ->
                    ep.path.trimStart('/').lowercase().contains(literal)
                }?.let { return it }

                // Priority 3: literal contains a normalized segment of the endpoint path
                candidates.firstOrNull { ep ->
                    val epNorm = ep.path.trimStart('/').lowercase()
                    literal.contains(epNorm)
                }?.let { return it }
            } else {
                // No literal found — match first candidate with this method
                // (handles [HttpGet] on action when route is inherited from controller)
                candidates.firstOrNull()?.let { return it }
            }
        }
        return null
    }

    override fun dispose() {}

    companion object {
        fun getInstance(project: Project): RouteXGutterService = project.service()
    }

    // ── Gutter icon renderer ──────────────────────────────────────────────────

    private inner class RouteXGutterRenderer(
        private val endpoint: ApiEndpoint,
        private val project: Project
    ) : GutterIconRenderer() {

        override fun getIcon(): Icon = AllIcons.RunConfigurations.TestState.Run

        override fun getTooltipText(): String {
            val defaultReq = RouteXStateService.getInstance(project).getDefaultRequest(endpoint.id)
            return if (defaultReq != null)
                "Run ${endpoint.method.name} ${endpoint.path} — ${defaultReq.name}"
            else
                "Open ${endpoint.method.name} ${endpoint.path} in RouteX"
        }

        override fun isNavigateAction() = true

        override fun getClickAction() = object : com.intellij.openapi.actionSystem.AnAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                ToolWindowManager.getInstance(project).getToolWindow("RouteX")?.show(null)

                val stateService = RouteXStateService.getInstance(project)
                val defaultReq   = stateService.getDefaultRequest(endpoint.id)

                val indexService = RouteIndexService.getInstance(project)
                if (defaultReq != null) {
                    indexService.runRequest(endpoint.id, defaultReq.id)
                } else {
                    indexService.selectEndpoint(endpoint.id)
                }
            }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RouteXGutterRenderer) return false
            return endpoint.id == other.endpoint.id
        }

        override fun hashCode() = endpoint.id.hashCode()

        override fun getAlignment() = Alignment.LEFT
    }
}
