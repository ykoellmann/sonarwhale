package com.sonarwhale.toolwindow

import com.google.gson.reflect.TypeToken
import com.google.gson.Gson
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.ApiParameter
import com.sonarwhale.model.AuthConfig
import com.sonarwhale.model.AuthMode
import com.sonarwhale.model.toJsonTemplate
import com.sonarwhale.model.ParameterLocation
import com.sonarwhale.model.SavedRequest
import com.sonarwhale.script.ConsoleOutput
import com.sonarwhale.script.ScriptLevel
import com.sonarwhale.script.ScriptPhase
import com.sonarwhale.model.HierarchyConfig
import com.sonarwhale.script.SonarwhaleScriptService
import com.sonarwhale.script.TestResult
import com.sonarwhale.service.AuthResolver
import com.sonarwhale.service.CollectionService
import com.sonarwhale.service.RouteIndexService
import com.sonarwhale.service.VariableResolver
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import kotlin.io.path.exists
import java.time.Duration
import java.util.UUID
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingWorker
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class RequestPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val stateService = SonarwhaleStateService.getInstance(project)
    private val gson = Gson()

    // Request identity
    private var currentEndpoint: ApiEndpoint? = null
    val currentEndpointId: String? get() = currentEndpoint?.id
    private var currentRequest: SavedRequest? = null

    // Backing field for request name — edited via the header in DetailPanel
    private var currentRequestName: String = "Default"
    private var previewMode = false

    private val setDefaultButton = JButton("★").apply {
        font = font.deriveFont(10f)
        toolTipText = "Mark as default (run by gutter icon)"
        isFocusable = false
    }

    // URL bar
    private val computedUrlField = JTextField().apply {
        isEditable = false
        foreground = JBColor.GRAY
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        toolTipText = "Full URL computed from base + route + parameters"
    }
    private val sendButton = JButton("Send").apply { font = font.deriveFont(Font.BOLD) }
    private val saveButton = JButton("Save").apply {
        font = font.deriveFont(11f)
        toolTipText = "Save current headers, body & param values"
    }

    // Auth state
    private var currentAuthConfig: AuthConfig = AuthConfig()
    private val authConfigPanel = AuthConfigPanel(
        auth = currentAuthConfig,
        onChange = { updated -> currentAuthConfig = updated; autoSave() }
    )

    // Tab panels
    private val paramsTable = ParamsTablePanel()
    private val headersTable = ParamsTablePanel()
    private val bodyPanel = BodyPanel(project)

    private val actionButtons: List<JComponent> = listOf(sendButton, saveButton, setDefaultButton)

    private val tabs = CollapsibleTabPane()

    // Remembered tab state — global (not per-request): name of last active tab + expanded flag
    private var lastTabName: String? = null
    private var lastTabExpanded: Boolean = true

    // Scripts-tab state
    private val requestPreChecks  = mutableMapOf<ScriptLevel, javax.swing.JCheckBox>()
    private val requestPostChecks = mutableMapOf<ScriptLevel, javax.swing.JCheckBox>()
    private lateinit var scriptPreBtn:  JButton
    private lateinit var scriptPostBtn: JButton
    private lateinit var scriptsPanel: JPanel
    private var scriptsToggleSection: JComponent? = null

    var onResponseReceived: ((Int, String, Long, String) -> Unit)? = null
    /** Called after a successful save — use to refresh the tree. */
    var onRequestSaved: (() -> Unit)? = null
    /** Called when the default state changes (true = is now default). */
    var onDefaultStateChanged: ((Boolean) -> Unit)? = null
    var onTestResultsReceived: ((List<TestResult>) -> Unit)? = null
    var onConsoleReceived: ((List<com.sonarwhale.script.ConsoleEntry>) -> Unit)? = null

    /** Called by DetailPanel when the user edits the name field in the header. */
    fun setRequestName(name: String) { currentRequestName = name }

    /** Called by DetailPanel's ★ button. */
    fun triggerSetDefault() = setAsDefault()

    private val recomputeListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = updateComputedUrl()
        override fun removeUpdate(e: DocumentEvent) = updateComputedUrl()
        override fun changedUpdate(e: DocumentEvent) = updateComputedUrl()
    }

    init {
        // Tabs are permanent — never removed/re-added. Params table always shown so the user
        // can add custom params even when the endpoint schema has none.
        tabs.addTab("Params", paramsTable)
        tabs.addTab("Headers", headersTable)
        tabs.addTab("Body", bodyPanel)
        tabs.addTab("Auth", authConfigPanel)
        tabs.addTab("Scripts", com.intellij.ui.components.JBScrollPane(buildScriptsTab()))

        tabs.onTabChanged = { name, exp -> lastTabName = name; lastTabExpanded = exp }

        add(buildTopBar(), BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        sendButton.addActionListener { sendRequest() }
        saveButton.addActionListener { if (previewMode) createNewRequest() else saveRequest() }
        setDefaultButton.addActionListener { setAsDefault() }
        paramsTable.addChangeListener { updateComputedUrl() }
    }

    private fun buildTopBar(): JPanel {
        val top = JPanel(BorderLayout(0, 2))
        top.border = JBUI.Borders.empty(4, 4, 0, 4)
        top.add(buildUrlBar(), BorderLayout.CENTER)
        return top
    }

    private fun buildUrlBar(): JPanel {
        val bar = JPanel(GridBagLayout())
        val gbc = GridBagConstraints()
        gbc.gridy = 0; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.anchor = GridBagConstraints.WEST

        gbc.gridx = 0; gbc.weightx = 1.0; gbc.insets = Insets(0, 0, 0, 6)
        bar.add(computedUrlField, gbc)

        gbc.gridx = 1; gbc.weightx = 0.0; gbc.insets = Insets(0, 0, 0, 4)
        bar.add(sendButton, gbc)

        gbc.gridx = 2; gbc.insets = Insets(0, 0, 0, 4)
        bar.add(saveButton, gbc)

        gbc.gridx = 3; gbc.insets = Insets(0, 0, 0, 0)
        bar.add(setDefaultButton, gbc)

        return bar
    }

    private fun buildScriptsTab(): JPanel {
        val panel = JPanel()
        panel.layout = javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        scriptPreBtn  = JButton("Pre-script").apply  { addActionListener { openOrCreateScript(ScriptPhase.PRE) } }
        scriptPostBtn = JButton("Post-script").apply { addActionListener { openOrCreateScript(ScriptPhase.POST) } }

        val btnRow = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0)).also {
            it.alignmentX = java.awt.Component.LEFT_ALIGNMENT
            it.add(scriptPreBtn); it.add(scriptPostBtn)
        }

        panel.add(btnRow)
        scriptsPanel = panel
        return panel
    }

    private fun setScriptsToggleLevels(levels: List<ScriptLevel>) {
        scriptsToggleSection?.let { scriptsPanel.remove(it) }
        requestPreChecks.clear()
        requestPostChecks.clear()

        val section = JPanel()
        section.layout = javax.swing.BoxLayout(section, javax.swing.BoxLayout.Y_AXIS)
        section.add(javax.swing.Box.createVerticalStrut(12))
        section.add(com.intellij.ui.components.JBLabel("Disable inherited:").apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
        section.add(javax.swing.Box.createVerticalStrut(4))
        section.add(buildToggleGrid(levels, requestPreChecks, requestPostChecks) { saveRequestToggles() })

        scriptsToggleSection = section
        scriptsPanel.add(section)
        scriptsPanel.revalidate()
        scriptsPanel.repaint()
    }

    private fun refreshScriptButtons() {
        val endpoint = currentEndpoint ?: return
        val request  = currentRequest  ?: SavedRequest(name = currentRequestName)
        val scriptService = SonarwhaleScriptService.getInstance(project)
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val preExists  = scriptService.getScriptPath(ScriptPhase.PRE,  ScriptLevel.REQUEST, endpoint.tags.firstOrNull() ?: "Default", endpoint, request).exists()
            val postExists = scriptService.getScriptPath(ScriptPhase.POST, ScriptLevel.REQUEST, endpoint.tags.firstOrNull() ?: "Default", endpoint, request).exists()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                scriptPreBtn.font  = scriptPreBtn.font.deriveFont(if (preExists)  Font.BOLD else Font.PLAIN)
                scriptPostBtn.font = scriptPostBtn.font.deriveFont(if (postExists) Font.BOLD else Font.PLAIN)
            }
        }
    }

    private fun buildToggleGrid(
        levels: List<ScriptLevel>,
        preChecks: MutableMap<ScriptLevel, javax.swing.JCheckBox>,
        postChecks: MutableMap<ScriptLevel, javax.swing.JCheckBox>,
        onChanged: () -> Unit
    ): JPanel {
        val grid = JPanel(GridBagLayout())
        grid.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.WEST
            insets = Insets(1, 0, 1, 12)
        }
        // Row 0: header row — empty label + one level name per column
        gbc.gridy = 0; gbc.gridx = 0; grid.add(JPanel(), gbc)
        levels.forEachIndexed { i, level ->
            gbc.gridx = i + 1
            grid.add(com.intellij.ui.components.JBLabel(level.name.lowercase().replaceFirstChar { it.uppercase() }), gbc)
        }
        // Row 1: Pre row
        gbc.gridy = 1; gbc.gridx = 0; grid.add(com.intellij.ui.components.JBLabel("Pre"), gbc)
        levels.forEachIndexed { i, level ->
            val cb = javax.swing.JCheckBox().apply { isSelected = true; addActionListener { onChanged() } }
            preChecks[level] = cb
            gbc.gridx = i + 1; grid.add(cb, gbc)
        }
        // Row 2: Post row
        gbc.gridy = 2; gbc.gridx = 0; grid.add(com.intellij.ui.components.JBLabel("Post"), gbc)
        levels.forEachIndexed { i, level ->
            val cb = javax.swing.JCheckBox().apply { isSelected = true; addActionListener { onChanged() } }
            postChecks[level] = cb
            gbc.gridx = i + 1; grid.add(cb, gbc)
        }
        return grid
    }

    private fun saveRequestToggles() {
        val req = currentRequest ?: return
        val disabledPre  = requestPreChecks.entries.filter  { !it.value.isSelected }.map { it.key.name }.toSet()
        val disabledPost = requestPostChecks.entries.filter { !it.value.isSelected }.map { it.key.name }.toSet()
        currentRequest = req.copy(config = req.config.copy(
            disabledPreLevels = disabledPre,
            disabledPostLevels = disabledPost
        ))
        autoSave()
    }

    private fun updateRequestToggles(disabledPre: Set<String>, disabledPost: Set<String>) {
        requestPreChecks.forEach  { (level, cb) -> cb.isSelected = !disabledPre.contains(level.name) }
        requestPostChecks.forEach { (level, cb) -> cb.isSelected = !disabledPost.contains(level.name) }
    }

    /** Show a specific named request for an endpoint. */
    fun showRequest(endpoint: ApiEndpoint, request: SavedRequest) {
        currentEndpoint = endpoint
        currentRequest = request

        currentRequestName = request.name
        updateDefaultButtonState(request.isDefault)

        val hasParams = endpoint.parameters.any { it.location == ParameterLocation.PATH || it.location == ParameterLocation.QUERY }
        val hasBody   = endpoint.requestBody != null

        // Params
        paramsTable.setRows(buildParamRows(endpoint, request.paramValues, request.paramEnabled))

        // Headers (auth pre-fill removed — handled by Auth tab)
        val headerRows = deserializeRows(request.headers) ?: endpoint.parameters
            .filter { it.location == ParameterLocation.HEADER }
            .map { NameValueRow(true, it.name, "", "header param") }
        headersTable.setRows(headerRows)

        // Auth tab
        currentAuthConfig = request.config.auth
        authConfigPanel.setAuth(
            newAuth = request.config.auth,
            newInherited = resolveInheritedAuthMode(endpoint)
        )

        // Scripts tab toggles + existence indicators
        // REQUEST level can disable all parent levels including ENDPOINT
        setScriptsToggleLevels(listOf(ScriptLevel.GLOBAL, ScriptLevel.COLLECTION, ScriptLevel.TAG, ScriptLevel.ENDPOINT))
        updateRequestToggles(request.config.disabledPreLevels, request.config.disabledPostLevels)
        refreshScriptButtons()

        // Body
        if (hasBody) {
            if (request.body.isNotEmpty()) {
                bodyPanel.setContent(BodyContent.Raw(request.body, request.bodyContentType))
                bodyPanel.setActiveMode(request.bodyMode)
            } else {
                bodyPanel.setContent(BodyContent.Raw(buildBodyTemplate(endpoint), "application/json"))
            }
        } else {
            val isGetOrHead = endpoint.method.name in listOf("GET", "HEAD")
            bodyPanel.setContent(if (isGetOrHead) BodyContent.None else BodyContent.Raw("", "application/json"))
        }

        applyTabState(hasBody)
        updateComputedUrl()
        saveButton.isEnabled = true
    }

    /**
     * Show endpoint schema without an existing request (no saved requests yet).
     * Pre-fills from schema; saving will create the first SavedRequest.
     */
    fun showEndpoint(endpoint: ApiEndpoint) {
        currentEndpoint = endpoint
        currentRequest = null

        currentRequestName = "Default"
        updateDefaultButtonState(true)

        val hasParams = endpoint.parameters.any { it.location == ParameterLocation.PATH || it.location == ParameterLocation.QUERY }
        val hasBody   = endpoint.requestBody != null

        paramsTable.setRows(buildParamRows(endpoint, emptyMap()))
        headersTable.setRows(endpoint.parameters
            .filter { it.location == ParameterLocation.HEADER }
            .map { NameValueRow(true, it.name, "", "header param") })

        // Auth tab
        val endpointConfig = stateService.getEndpointConfig(endpoint.id)
        currentAuthConfig = endpointConfig.config.auth
        authConfigPanel.setAuth(
            newAuth = endpointConfig.config.auth,
            newInherited = resolveInheritedAuthMode(endpoint)
        )

        // Scripts tab toggles + existence indicators
        // ENDPOINT level can only disable parent levels, not itself
        setScriptsToggleLevels(listOf(ScriptLevel.GLOBAL, ScriptLevel.COLLECTION, ScriptLevel.TAG))
        updateRequestToggles(emptySet(), emptySet())
        refreshScriptButtons()

        if (hasBody) {
            bodyPanel.setContent(BodyContent.Raw(buildBodyTemplate(endpoint), "application/json"))
        } else {
            bodyPanel.setContent(BodyContent.None)
        }

        applyTabState(hasBody)
        updateComputedUrl()
        saveButton.isEnabled = true
    }

    /** Restores the last user-selected tab state, or defaults to Body on first load. */
    private fun applyTabState(hasBody: Boolean) {
        if (lastTabName == null) {
            // First load — default to Body if endpoint has a body, otherwise leave Params selected
            if (hasBody) tabs.selectedIndex = tabs.indexOfComponent(bodyPanel)
        } else {
            tabs.restoreState(lastTabName, lastTabExpanded)
        }
    }

    fun setPreviewMode(preview: Boolean) {
        previewMode = preview
        actionButtons.forEach { it.isVisible = !preview }
        saveButton.isVisible = true   // always visible — text changes based on mode
        saveButton.text = if (preview) "New Request" else "Save"
        paramsTable.setReadOnly(preview)
        headersTable.setReadOnly(preview)
        bodyPanel.setReadOnly(preview)
    }

    /** Trigger a send programmatically (used by gutter icon). */
    fun triggerSend() = sendRequest()

    /** Re-evaluate the computed URL with the current active environment. */
    fun refreshEnvironment() = updateComputedUrl()

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun updateDefaultButtonState(isDefault: Boolean) {
        setDefaultButton.isEnabled = !isDefault
        setDefaultButton.foreground = if (isDefault)
            JBColor(Color(0xCC, 0xAA, 0x00), Color(0xFF, 0xDD, 0x55))
        else
            JBColor.GRAY
        setDefaultButton.toolTipText = if (isDefault) "This is the default request" else "Mark as default (run by gutter icon)"
    }


    private fun buildParamRows(
        endpoint: ApiEndpoint,
        savedValues: Map<String, String>,
        savedEnabled: Map<String, Boolean> = emptyMap()
    ): List<NameValueRow> {
        val pathParams  = endpoint.parameters.filter { it.location == ParameterLocation.PATH }
        val queryParams = endpoint.parameters.filter { it.location == ParameterLocation.QUERY }
        return (pathParams + queryParams).map { param ->
            val value   = savedValues[param.name] ?: param.schema?.example?.toString() ?: ""
            val enabled = savedEnabled[param.name] ?: true
            NameValueRow(enabled = enabled, key = param.name, value = value, description = buildParamDescription(param))
        }
    }

    private fun buildParamDescription(param: ApiParameter): String {
        val parts = mutableListOf<String>()
        parts += param.location.name.lowercase()
        if (param.required) parts += "required"
        param.schema?.type?.let { parts += it }
        return parts.joinToString(", ")
    }

    private fun buildBodyTemplate(endpoint: ApiEndpoint): String {
        return endpoint.requestBody?.toJsonTemplate() ?: "{}"
    }

    private fun resolveInheritedAuthMode(endpoint: ApiEndpoint): AuthMode {
        val colId = RouteIndexService.getInstance(project).getCollectionId(endpoint.id) ?: return AuthMode.NONE
        val state = SonarwhaleStateService.getInstance(project)
        val authResolver = AuthResolver.getInstance(project)
        return authResolver.resolve(
            requestAuth = AuthConfig(mode = AuthMode.INHERIT),
            endpointAuth = state.getEndpointConfig(endpoint.id).config.auth,
            tagAuth = endpoint.tags.firstOrNull()?.let { state.getTagConfig(it).config.auth } ?: AuthConfig(),
            collectionAuth = CollectionService.getInstance(project).getById(colId)?.config?.auth ?: AuthConfig(),
            globalAuth = state.getGlobalConfig().config.auth
        ).mode
    }

    private fun updateComputedUrl() {
        val endpoint = currentEndpoint ?: run { computedUrlField.text = ""; return }
        val colId = RouteIndexService.getInstance(project).getCollectionId(endpoint.id) ?: ""
        val base = CollectionService.getInstance(project).getBaseUrl(colId)?.trimEnd('/') ?: ""
        var route = endpoint.path

        val paramRows = paramsTable.getRows()
        endpoint.parameters.filter { it.location == ParameterLocation.PATH }.forEach { param ->
            val row = paramRows.firstOrNull { it.key == param.name }
            val v = if (row?.enabled == true) row.value else ""
            val pattern = Regex("\\{${Regex.escape(param.name)}(?::[^}]*)??\\??\\}")
            route = pattern.replace(route) { if (v.isEmpty()) it.value else v }
        }

        val query = endpoint.parameters
            .filter { it.location == ParameterLocation.QUERY }
            .mapNotNull { param ->
                val row = paramRows.firstOrNull { it.key == param.name } ?: return@mapNotNull null
                if (!row.enabled || row.value.isEmpty()) null
                else "${URLEncoder.encode(param.name, "UTF-8")}=${URLEncoder.encode(row.value, "UTF-8")}"
            }

        val assembled = base + route + if (query.isEmpty()) "" else "?" + query.joinToString("&")
        val varResolver = VariableResolver.getInstance(project)
        val varMap = varResolver.buildMap(colId, endpoint.id, currentRequest?.id)
        val resolved = varResolver.resolve(assembled, varMap)
        computedUrlField.text = resolved
        computedUrlField.foreground = if (resolved.contains("{{"))
            JBColor(java.awt.Color(0xCC, 0x33, 0x00), java.awt.Color(0xFF, 0x66, 0x44))
        else JBColor.GRAY
    }

    private fun openOrCreateScript(phase: ScriptPhase, level: ScriptLevel = ScriptLevel.REQUEST) {
        val endpoint = currentEndpoint ?: return
        val request  = currentRequest ?: SavedRequest(name = currentRequestName)
        val scriptService = SonarwhaleScriptService.getInstance(project)
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Creating script…", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                val path = scriptService.getOrCreateScript(endpoint, request, phase, level)
                val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
        })
    }

    private fun setAsDefault() {
        val endpoint = currentEndpoint ?: return
        val req = currentRequest ?: return
        stateService.setDefault(endpoint.id, req.id)
        currentRequest = req.copy(isDefault = true)
        updateDefaultButtonState(true)
        onDefaultStateChanged?.invoke(true)
        onRequestSaved?.invoke()
    }

    private fun createNewRequest() {
        val endpoint = currentEndpoint ?: return
        val existingNames = stateService.getRequests(endpoint.id).map { it.name }.toSet()

        var inputName: String? = null
        while (inputName == null) {
            val input = javax.swing.JOptionPane.showInputDialog(
                this,
                "Request name:",
                "New Request",
                javax.swing.JOptionPane.PLAIN_MESSAGE
            )?.trim()
            when {
                input == null -> return
                input.isEmpty() -> javax.swing.JOptionPane.showMessageDialog(
                    this, "Name cannot be empty.", "Invalid Name", javax.swing.JOptionPane.WARNING_MESSAGE
                )
                input in existingNames -> javax.swing.JOptionPane.showMessageDialog(
                    this, "A request named \"$input\" already exists for this endpoint.",
                    "Duplicate Name", javax.swing.JOptionPane.WARNING_MESSAGE
                )
                else -> inputName = input
            }
        }

        currentRequestName = inputName
        saveRequest()
    }

    private fun autoSave() {
        if (previewMode) {
            // In preview mode (no saved request loaded), save auth to EndpointConfig
            val ep = currentEndpoint ?: return
            val existing = stateService.getEndpointConfig(ep.id)
            stateService.setEndpointConfig(existing.copy(config = existing.config.copy(auth = currentAuthConfig)))
        } else {
            // In request mode, save to the saved request
            if (currentRequest != null) saveRequest()
        }
    }

    private fun saveRequest() {
        val endpoint = currentEndpoint ?: return

        val allParamRows = paramsTable.getRows().filter { it.key.isNotEmpty() }
        val paramValues = allParamRows
            .filter { it.value.isNotEmpty() }
            .associate { it.key to it.value }
        val paramEnabled = allParamRows.associate { it.key to it.enabled }

        val id = currentRequest?.id ?: UUID.randomUUID().toString()
        val name = currentRequestName.takeIf { it.isNotBlank() } ?: "Default"
        val isDefault = currentRequest?.isDefault ?: true   // first request is always default

        val bodyContent = bodyPanel.getContent()
        val req = SavedRequest(
            id = id,
            name = name,
            isDefault = isDefault,
            headers = serializeRows(headersTable.getRows()),
            bodyMode = bodyPanel.getActiveMode(),
            bodyContentType = bodyPanel.getRawContentType(),
            body = when (bodyContent) {
                is BodyContent.Raw -> bodyContent.text
                else -> ""
            },
            paramValues = paramValues,
            paramEnabled = paramEnabled,
            config = (currentRequest?.config ?: com.sonarwhale.model.HierarchyConfig()).copy(auth = currentAuthConfig)
        )

        currentRequest = req
        stateService.upsertRequest(endpoint.id, req)
        onRequestSaved?.invoke()

    }

    private fun sendRequest() {
        val endpoint = currentEndpoint ?: return
        val rawUrl = computedUrlField.text.trim()
        if (rawUrl.isEmpty()) return

        saveRequest()
        sendButton.isEnabled = false

        val colId = RouteIndexService.getInstance(project).getCollectionId(endpoint.id) ?: ""
        val varResolver = VariableResolver.getInstance(project)
        val authResolver = AuthResolver.getInstance(project)
        val varMap = varResolver.buildMap(colId, endpoint.id, currentRequest?.id)

        val effectiveAuth = authResolver.resolve(colId, endpoint.id, currentRequest?.id)
        val resolvedUrl = varResolver.resolve(rawUrl, varMap)

        val headerRows = headersTable.getRows()
            .filter { it.enabled && it.key.isNotEmpty() }
            .map { it.copy(value = varResolver.resolve(it.value, varMap)) }
        val bodyContent = bodyPanel.getContent().let { bc ->
            when (bc) {
                is BodyContent.Raw      -> bc.copy(text = varResolver.resolve(bc.text, varMap))
                is BodyContent.FormData -> bc.copy(rows = bc.rows.map { r -> r.copy(value = varResolver.resolve(r.value, varMap)) })
                else -> bc
            }
        }

        val savedRequest = currentRequest ?: SavedRequest(name = currentRequestName)

        // Effective disabled script levels = union of endpoint config + request config
        val epCfg  = stateService.getEndpointConfig(endpoint.id)
        val reqCfg = currentRequest?.config ?: HierarchyConfig()
        val effectiveDisabledPre  = epCfg.config.disabledPreLevels  + reqCfg.disabledPreLevels
        val effectiveDisabledPost = epCfg.config.disabledPostLevels + reqCfg.disabledPostLevels

        val scriptService = SonarwhaleScriptService.getInstance(project)
        val consoleOutput = ConsoleOutput()

        object : SwingWorker<Pair<Triple<Int, String, Long>, String>, Unit>() {
            private var testResults: List<TestResult> = emptyList()
            private var scriptContext: com.sonarwhale.script.ScriptContext? = null

            override fun doInBackground(): Pair<Triple<Int, String, Long>, String> {
                // ── Pre-scripts ────────────────────────────────────────────────
                val initialHeaders = headerRows.associate { it.key.trim() to it.value.trim() }.toMutableMap()
                val initialBody = when (val bc = bodyContent) {
                    is BodyContent.Raw -> bc.text
                    else -> ""
                }
                val ctx = scriptService.executePreScripts(
                    endpoint       = endpoint,
                    request        = savedRequest,
                    url            = resolvedUrl,
                    headers        = initialHeaders,
                    body           = initialBody,
                    varMap         = varMap,
                    collectionId   = colId,
                    disabledLevels = effectiveDisabledPre,
                    console        = consoleOutput
                )
                scriptContext = ctx

                // Merge env changes from pre-scripts so tokens set by sw.env.set() are available for auth
                val postScriptVarMap = varMap.toMutableMap().also { it.putAll(ctx.envSnapshot) }

                val finalUrl     = ctx.request.url
                val finalHeaders = ctx.request.headers
                val finalBody    = ctx.request.body

                // ── HTTP Request ───────────────────────────────────────────────
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                val builder = HttpRequest.newBuilder()
                    .uri(URI.create(finalUrl))
                    .timeout(Duration.ofSeconds(30))

                // Apply auth headers using postScriptVarMap so pre-script env changes are visible
                val authUrlForBuilder = StringBuilder(finalUrl)
                authResolver.applyToRequest(builder, authUrlForBuilder, effectiveAuth, postScriptVarMap, varResolver)
                // Apply non-auth headers from script context
                finalHeaders.forEach { (k, v) -> runCatching { builder.header(k, v) } }
                val hasContentType = finalHeaders.keys.any { it.equals("content-type", ignoreCase = true) }

                when (val bc = bodyContent) {
                    is BodyContent.None -> if (finalBody.isNotEmpty()) {
                        // Pre-script injected a body — send it even though the body panel is empty
                        if (!hasContentType) builder.header("Content-Type", "application/json")
                        val publisher = HttpRequest.BodyPublishers.ofString(finalBody)
                        when (endpoint.method.name) {
                            "POST"        -> builder.POST(publisher)
                            "PUT"         -> builder.PUT(publisher)
                            "DELETE"      -> builder.DELETE()
                            "GET", "HEAD" -> builder.GET()
                            else          -> builder.method(endpoint.method.name, publisher)
                        }
                    } else when (endpoint.method.name) {
                        "GET", "HEAD" -> builder.GET()
                        "DELETE"      -> builder.DELETE()
                        else          -> builder.method(endpoint.method.name, HttpRequest.BodyPublishers.noBody())
                    }
                    is BodyContent.Raw -> {
                        if (!hasContentType) builder.header("Content-Type", bc.contentType)
                        val publisher = HttpRequest.BodyPublishers.ofString(finalBody)
                        when (endpoint.method.name) {
                            "POST"        -> builder.POST(publisher)
                            "PUT"         -> builder.PUT(publisher)
                            "DELETE"      -> builder.DELETE()
                            "GET", "HEAD" -> builder.GET()
                            else          -> builder.method(endpoint.method.name, publisher)
                        }
                    }
                    is BodyContent.FormData -> {
                        if (!hasContentType) builder.header("Content-Type", "application/x-www-form-urlencoded")
                        val encoded = bc.rows.filter { it.enabled && it.key.isNotEmpty() }
                            .joinToString("&") {
                                "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
                            }
                        val publisher = HttpRequest.BodyPublishers.ofString(encoded)
                        when (endpoint.method.name) {
                            "POST" -> builder.POST(publisher)
                            "PUT"  -> builder.PUT(publisher)
                            else   -> builder.method(endpoint.method.name, publisher)
                        }
                    }
                    is BodyContent.Binary -> {
                        val path = Paths.get(bc.filePath)
                        val publisher = HttpRequest.BodyPublishers.ofFile(path)
                        when (endpoint.method.name) {
                            "POST" -> builder.POST(publisher)
                            "PUT"  -> builder.PUT(publisher)
                            else   -> builder.method(endpoint.method.name, publisher)
                        }
                    }
                }

                val start = System.currentTimeMillis()
                val response = try {
                    client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - start
                    consoleOutput.http(
                        endpoint.method.name, finalUrl, 0, duration,
                        finalHeaders, finalBody.ifEmpty { null }, emptyMap(), "", e.message
                    )
                    throw e
                }
                val duration = System.currentTimeMillis() - start

                // ── Post-scripts ───────────────────────────────────────────────
                val responseHeaders = response.headers().map()
                    .mapValues { (_, vs) -> vs.firstOrNull() ?: "" }

                // Record main request between pre and post scripts (timeline order)
                consoleOutput.http(
                    endpoint.method.name, finalUrl, response.statusCode(), duration,
                    finalHeaders, finalBody.ifEmpty { null },
                    responseHeaders, response.body(), null
                )

                testResults = scriptService.executePostScripts(
                    endpoint        = endpoint,
                    request         = savedRequest,
                    statusCode      = response.statusCode(),
                    responseHeaders = responseHeaders,
                    responseBody    = response.body(),
                    scriptContext   = ctx,
                    collectionId    = colId,
                    originalVarMap  = varMap,
                    disabledLevels  = effectiveDisabledPost,
                    console         = consoleOutput
                )

                val contentType: String = response.headers().firstValue("content-type").orElse("") ?: ""
                return Triple(response.statusCode(), response.body(), duration) to contentType
            }

            override fun done() {
                sendButton.isEnabled = true
                runCatching {
                    val result = get()
                    val (status, body, duration) = result.first
                    val contentType = result.second
                    onResponseReceived?.invoke(status, body, duration, contentType)
                    onTestResultsReceived?.invoke(testResults)
                    onConsoleReceived?.invoke(consoleOutput.entries)
                }.onFailure { e ->
                    onResponseReceived?.invoke(0, describeError(e), 0, "")
                    onTestResultsReceived?.invoke(emptyList())
                    onConsoleReceived?.invoke(consoleOutput.entries)
                }
            }
        }.execute()
    }

    // ── Error helpers ─────────────────────────────────────────────────────────

    private fun describeError(e: Throwable): String {
        // SwingWorker.get() wraps exceptions in ExecutionException — unwrap to the real cause.
        val cause = generateSequence(e) { it.cause }
            .firstOrNull { it !is java.util.concurrent.ExecutionException } ?: e
        return when (cause) {
            is java.net.ConnectException ->
                "Could not connect to server.\n\nMake sure the server is running and the base URL is correct.\n\nDetails: ${cause.message ?: cause.javaClass.simpleName}"
            is java.net.http.HttpConnectTimeoutException ->
                "Connection timed out.\n\nThe server did not accept the connection within the timeout period.\n\nURL: ${computedUrlField.text}"
            is java.net.SocketTimeoutException ->
                "Request timed out (30s).\n\nThe server accepted the connection but did not respond in time."
            is java.net.UnknownHostException ->
                "Unknown host: ${cause.message}\n\nCheck the base URL for typos."
            is java.net.MalformedURLException, is java.lang.IllegalArgumentException ->
                "Invalid URL: ${computedUrlField.text}\n\n${cause.message}"
            is java.net.http.HttpTimeoutException ->
                "Request timed out.\n\n${cause.message}"
            else ->
                "${cause.javaClass.simpleName}: ${cause.message ?: "(no message)"}"
        }
    }

    // ── Serialization helpers ─────────────────────────────────────────────────

    private fun serializeRows(rows: List<NameValueRow>): String = gson.toJson(rows)

    private fun deserializeRows(json: String): List<NameValueRow>? {
        if (json.isBlank()) return null
        if (!json.trimStart().startsWith("[")) {
            // Legacy "Name: Value" lines
            return json.lines()
                .filter { it.contains(":") }
                .map { line ->
                    val idx = line.indexOf(':')
                    NameValueRow(true, line.substring(0, idx).trim(), line.substring(idx + 1).trim(), "")
                }
                .takeIf { it.isNotEmpty() }
        }
        return runCatching {
            val type = object : TypeToken<List<NameValueRow>>() {}.type
            gson.fromJson<List<NameValueRow>>(json, type)
        }.getOrNull()
    }
}

