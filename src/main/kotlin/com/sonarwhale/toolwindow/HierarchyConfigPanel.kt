package com.sonarwhale.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.license.LicenseService
import com.sonarwhale.license.PremiumFeature
import com.sonarwhale.license.PremiumGate
import com.sonarwhale.model.HierarchyConfig
import com.sonarwhale.script.ScriptPhase
import com.sonarwhale.script.SonarwhaleScriptService
import java.awt.BorderLayout
import javax.swing.*

/**
 * Tabbed config panel shown when a hierarchy node (Global, Collection, Controller,
 * Endpoint, or SavedRequest) is selected. Tabs: Variables, Auth, Scripts.
 *
 * [onSave] is called whenever the user modifies config. Caller persists the result.
 */
data class ScriptContext(
    val level: com.sonarwhale.script.ScriptLevel,
    val collectionId: String = "",
    val tag: String? = null,
    val method: String? = null,
    val path: String? = null,
    val requestName: String? = null
)

class HierarchyConfigPanel(
    private val project: Project,
    private var config: HierarchyConfig,
    private val onSave: (HierarchyConfig) -> Unit,
    private val scriptContext: ScriptContext? = null,
    showOwnTabs: Boolean = true
) : JPanel(BorderLayout()) {

    private val variablesPanel = VariablesTablePanel(project)
    private val preCheckboxes  = mutableMapOf<com.sonarwhale.script.ScriptLevel, javax.swing.JCheckBox>()
    private val postCheckboxes = mutableMapOf<com.sonarwhale.script.ScriptLevel, javax.swing.JCheckBox>()
    private val authPanel = AuthConfigPanel(
        auth = config.auth,
        onChange = { updatedAuth ->
            config = config.copy(auth = updatedAuth)
            onSave(config)
        }
    )

    val variablesTab: JComponent = JBScrollPane(variablesPanel)
    val authTab: JComponent get() = authPanel
    lateinit var scriptsTab: JComponent
        private set

    init {
        variablesPanel.setVariables(config.variables)
        variablesPanel.onChange = { updated ->
            config = config.copy(variables = updated)
            onSave(config)
        }

        scriptsTab = JBScrollPane(buildScriptsTab())

        if (showOwnTabs) {
            val tabs = CollapsibleTabPane()
            tabs.addTab("Variables", variablesTab)
            tabs.addTab("Auth", authTab)
            tabs.addTab("Scripts", scriptsTab)
            add(tabs, BorderLayout.CENTER)
        }
    }

    fun setConfig(newConfig: HierarchyConfig) {
        config = newConfig
        variablesPanel.setVariables(newConfig.variables)
        authPanel.setAuth(newConfig.auth)
        preCheckboxes.forEach  { (level, cb) -> cb.isSelected = !newConfig.disabledPreLevels.contains(level.name) }
        postCheckboxes.forEach { (level, cb) -> cb.isSelected = !newConfig.disabledPostLevels.contains(level.name) }
    }

    private fun buildScriptsTab(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = JBUI.Borders.empty(8)

        val isNonGlobal = scriptContext != null && scriptContext.level != com.sonarwhale.script.ScriptLevel.GLOBAL
        val locked = isNonGlobal && !LicenseService.getInstance().isUnlocked(PremiumFeature.FULL_SCRIPTS)

        val preBtn = JButton("Open Pre-Script").apply {
            alignmentX = LEFT_ALIGNMENT
            addActionListener { openScript(ScriptPhase.PRE) }
        }
        val postBtn = JButton("Open Post-Script").apply {
            alignmentX = LEFT_ALIGNMENT
            addActionListener { openScript(ScriptPhase.POST) }
        }

        if (locked) {
            PremiumGate.applyTo(preBtn, PremiumFeature.FULL_SCRIPTS, locked = true)
            PremiumGate.applyTo(postBtn, PremiumFeature.FULL_SCRIPTS, locked = true)
        }

        panel.add(preBtn)
        panel.add(Box.createVerticalStrut(4))
        panel.add(postBtn)

        val parentLevels: List<com.sonarwhale.script.ScriptLevel> = when (scriptContext?.level) {
            com.sonarwhale.script.ScriptLevel.COLLECTION ->
                listOf(com.sonarwhale.script.ScriptLevel.GLOBAL)
            com.sonarwhale.script.ScriptLevel.TAG ->
                listOf(com.sonarwhale.script.ScriptLevel.GLOBAL, com.sonarwhale.script.ScriptLevel.COLLECTION)
            com.sonarwhale.script.ScriptLevel.ENDPOINT ->
                listOf(com.sonarwhale.script.ScriptLevel.GLOBAL, com.sonarwhale.script.ScriptLevel.COLLECTION, com.sonarwhale.script.ScriptLevel.TAG)
            else -> emptyList()
        }

        if (parentLevels.isNotEmpty()) {
            panel.add(Box.createVerticalStrut(12))
            panel.add(javax.swing.JLabel("Disable inherited:").apply { alignmentX = LEFT_ALIGNMENT })
            panel.add(Box.createVerticalStrut(4))
            panel.add(buildScriptToggleGrid(
                parentLevels,
                preCheckboxes,
                postCheckboxes,
                isPreEnabled  = { level -> !config.disabledPreLevels.contains(level.name) },
                isPostEnabled = { level -> !config.disabledPostLevels.contains(level.name) },
                onChanged     = { onToggleChanged() }
            ))
        }

        return panel
    }

    private fun onToggleChanged() {
        val disabledPre  = preCheckboxes.entries.filter  { !it.value.isSelected }.map { it.key.name }.toSet()
        val disabledPost = postCheckboxes.entries.filter { !it.value.isSelected }.map { it.key.name }.toSet()
        config = config.copy(disabledPreLevels = disabledPre, disabledPostLevels = disabledPost)
        onSave(config)
    }

    private fun openScript(phase: ScriptPhase) {
        val ctx = scriptContext ?: return
        if (ctx.level != com.sonarwhale.script.ScriptLevel.GLOBAL &&
            !LicenseService.getInstance().isUnlocked(PremiumFeature.FULL_SCRIPTS)
        ) {
            LicenseService.requestLicense("Script hierarchy (tag/endpoint level) requires Sonarwhale Premium.")
            return
        }
        val scriptService = SonarwhaleScriptService.getInstance(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val path = scriptService.getOrCreateScript(
                phase = phase,
                level = ctx.level,
                tag = ctx.tag,
                collectionId = ctx.collectionId
            )
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(vf, true)
            }
        }
    }
}

// ── Shared script toggle grid ─────────────────────────────────────────────────

internal fun buildScriptToggleGrid(
    levels: List<com.sonarwhale.script.ScriptLevel>,
    preChecks: MutableMap<com.sonarwhale.script.ScriptLevel, javax.swing.JCheckBox>,
    postChecks: MutableMap<com.sonarwhale.script.ScriptLevel, javax.swing.JCheckBox>,
    isPreEnabled:  (com.sonarwhale.script.ScriptLevel) -> Boolean = { true },
    isPostEnabled: (com.sonarwhale.script.ScriptLevel) -> Boolean = { true },
    onChanged: () -> Unit
): javax.swing.JPanel {
    val grid = javax.swing.JPanel(java.awt.GridBagLayout())
    grid.alignmentX = java.awt.Component.LEFT_ALIGNMENT
    val gbc = java.awt.GridBagConstraints().apply {
        anchor = java.awt.GridBagConstraints.WEST
        insets = java.awt.Insets(1, 0, 1, 12)
    }
    gbc.gridy = 0; gbc.gridx = 0; grid.add(javax.swing.JPanel(), gbc)
    levels.forEachIndexed { i, level ->
        gbc.gridx = i + 1
        grid.add(com.intellij.ui.components.JBLabel(
            level.name.lowercase().replaceFirstChar { it.uppercase() }), gbc)
    }
    gbc.gridy = 1; gbc.gridx = 0; grid.add(com.intellij.ui.components.JBLabel("Pre"), gbc)
    levels.forEachIndexed { i, level ->
        val cb = javax.swing.JCheckBox().apply { isSelected = isPreEnabled(level); addActionListener { onChanged() } }
        preChecks[level] = cb
        gbc.gridx = i + 1; grid.add(cb, gbc)
    }
    gbc.gridy = 2; gbc.gridx = 0; grid.add(com.intellij.ui.components.JBLabel("Post"), gbc)
    levels.forEachIndexed { i, level ->
        val cb = javax.swing.JCheckBox().apply { isSelected = isPostEnabled(level); addActionListener { onChanged() } }
        postChecks[level] = cb
        gbc.gridx = i + 1; grid.add(cb, gbc)
    }
    return grid
}
