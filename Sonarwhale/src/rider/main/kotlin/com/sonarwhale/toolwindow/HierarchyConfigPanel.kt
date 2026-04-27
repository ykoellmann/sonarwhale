package com.sonarwhale.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.model.HierarchyConfig
import com.sonarwhale.model.VariableEntry
import com.sonarwhale.script.ScriptPhase
import com.sonarwhale.script.SonarwhaleScriptService
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

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
    private val scriptContext: ScriptContext? = null
) : JPanel(BorderLayout()) {

    private val tabs = CollapsibleTabPane()
    private val variablesPanel = VariablesTablePanel()
    private val preCheckboxes  = mutableMapOf<com.sonarwhale.script.ScriptLevel, javax.swing.JCheckBox>()
    private val postCheckboxes = mutableMapOf<com.sonarwhale.script.ScriptLevel, javax.swing.JCheckBox>()
    private val authPanel = AuthConfigPanel(
        auth = config.auth,
        onChange = { updatedAuth ->
            config = config.copy(auth = updatedAuth)
            onSave(config)
        }
    )

    init {
        variablesPanel.setVariables(config.variables)
        variablesPanel.onChange = { updated ->
            config = config.copy(variables = updated)
            onSave(config)
        }

        tabs.addTab("Variables", JBScrollPane(variablesPanel))
        tabs.addTab("Auth", authPanel)
        tabs.addTab("Scripts", JBScrollPane(buildScriptsTab()))
        add(tabs, BorderLayout.CENTER)
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

        val preBtn = JButton("Open Pre-Script").apply {
            alignmentX = LEFT_ALIGNMENT
            addActionListener { openScript(ScriptPhase.PRE) }
        }
        val postBtn = JButton("Open Post-Script").apply {
            alignmentX = LEFT_ALIGNMENT
            addActionListener { openScript(ScriptPhase.POST) }
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
            panel.add(buildToggleGrid(parentLevels))
        }

        return panel
    }

    private fun buildToggleGrid(levels: List<com.sonarwhale.script.ScriptLevel>): JPanel {
        val grid = JPanel(java.awt.GridBagLayout())
        grid.alignmentX = LEFT_ALIGNMENT
        val gbc = java.awt.GridBagConstraints().apply {
            anchor = java.awt.GridBagConstraints.WEST
            insets = java.awt.Insets(2, 0, 2, 8)
        }
        gbc.gridy = 0; gbc.gridx = 0; grid.add(JPanel(), gbc)
        gbc.gridx = 1; grid.add(javax.swing.JLabel("Pre"), gbc)
        gbc.gridx = 2; grid.add(javax.swing.JLabel("Post"), gbc)

        levels.forEachIndexed { i, level ->
            val preCb  = javax.swing.JCheckBox().apply {
                isSelected = !config.disabledPreLevels.contains(level.name)
                addActionListener { onToggleChanged() }
            }
            val postCb = javax.swing.JCheckBox().apply {
                isSelected = !config.disabledPostLevels.contains(level.name)
                addActionListener { onToggleChanged() }
            }
            preCheckboxes[level]  = preCb
            postCheckboxes[level] = postCb

            gbc.gridy = i + 1
            gbc.gridx = 0; grid.add(javax.swing.JLabel(level.name.lowercase().replaceFirstChar { it.uppercase() }), gbc)
            gbc.gridx = 1; grid.add(preCb,  gbc)
            gbc.gridx = 2; grid.add(postCb, gbc)
        }
        return grid
    }

    private fun onToggleChanged() {
        val disabledPre  = preCheckboxes.entries.filter  { !it.value.isSelected }.map { it.key.name }.toSet()
        val disabledPost = postCheckboxes.entries.filter { !it.value.isSelected }.map { it.key.name }.toSet()
        config = config.copy(disabledPreLevels = disabledPre, disabledPostLevels = disabledPost)
        onSave(config)
    }

    private fun openScript(phase: ScriptPhase) {
        val ctx = scriptContext ?: return
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

// ── Variables table ───────────────────────────────────────────────────────────

private class VariablesTablePanel : JPanel(BorderLayout()) {

    var onChange: ((List<VariableEntry>) -> Unit)? = null
    private val tableModel = VariablesTableModel()

    init {
        val table = com.intellij.ui.table.JBTable(tableModel).apply {
            setShowGrid(false)
            rowHeight = 22
            columnModel.getColumn(0).preferredWidth = 30   // enabled checkbox
            columnModel.getColumn(1).preferredWidth = 140  // key
            columnModel.getColumn(2).preferredWidth = 200  // value
        }

        tableModel.addTableModelListener {
            onChange?.invoke(tableModel.getVariables())
        }

        val addBtn = JButton(com.intellij.icons.AllIcons.General.Add).apply {
            isBorderPainted = false; isContentAreaFilled = false
            addActionListener {
                tableModel.addRow()
                table.editCellAt(tableModel.rowCount - 1, 1)
            }
        }
        val removeBtn = JButton(com.intellij.icons.AllIcons.General.Remove).apply {
            isBorderPainted = false; isContentAreaFilled = false
            addActionListener {
                val row = table.selectedRow
                if (row >= 0) tableModel.removeRow(row)
            }
        }

        val toolbar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0))
        toolbar.add(addBtn); toolbar.add(removeBtn)
        toolbar.border = JBUI.Borders.customLineBottom(JBColor.border())

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun setVariables(vars: List<VariableEntry>) = tableModel.setVariables(vars)
}

private class VariablesTableModel : AbstractTableModel() {
    private val rows = mutableListOf<VariableEntry>()
    private val cols = arrayOf("", "Key", "Value")

    fun setVariables(vars: List<VariableEntry>) {
        rows.clear(); rows.addAll(vars); fireTableDataChanged()
    }

    fun getVariables(): List<VariableEntry> = rows.toList()

    fun addRow() { rows.add(VariableEntry()); fireTableRowsInserted(rows.size - 1, rows.size - 1) }

    fun removeRow(idx: Int) { if (idx in rows.indices) { rows.removeAt(idx); fireTableRowsDeleted(idx, idx) } }

    override fun getRowCount() = rows.size
    override fun getColumnCount() = 3
    override fun getColumnName(col: Int) = cols[col]
    override fun isCellEditable(row: Int, col: Int) = true
    override fun getColumnClass(col: Int) = if (col == 0) Boolean::class.java else String::class.java

    override fun getValueAt(row: Int, col: Int): Any = when (col) {
        0 -> rows[row].enabled
        1 -> rows[row].key
        2 -> rows[row].value
        else -> ""
    }

    override fun setValueAt(value: Any?, row: Int, col: Int) {
        if (row !in rows.indices) return
        rows[row] = when (col) {
            0 -> rows[row].copy(enabled = value as Boolean)
            1 -> rows[row].copy(key = value as String)
            2 -> rows[row].copy(value = value as String)
            else -> rows[row]
        }
        fireTableCellUpdated(row, col)
    }
}
