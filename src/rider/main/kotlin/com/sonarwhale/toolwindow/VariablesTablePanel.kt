package com.sonarwhale.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.model.VariableEntry
import com.sonarwhale.service.SecretStorageService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.TableCellEditor

class VariablesTablePanel(private val project: Project) : JPanel(BorderLayout()) {

    var onChange: ((List<VariableEntry>) -> Unit)? = null
    private val model = VariablesModel()
    private val table: com.intellij.ui.table.JBTable
    private var suppressChanges = false

    private val projectHash: String get() = project.basePath ?: project.name

    init {
        table = com.intellij.ui.table.JBTable(model).apply {
            setShowGrid(false)
            rowHeight = 22
            putClientProperty("terminateEditOnFocusLost", true)
            // Col 0: Enabled
            columnModel.getColumn(0).apply { preferredWidth = 28; maxWidth = 28; minWidth = 28 }
            // Col 1: Key
            columnModel.getColumn(1).preferredWidth = 130
            // Col 2: Value
            columnModel.getColumn(2).preferredWidth = 180
            columnModel.getColumn(2).cellRenderer = ValueRenderer()
            columnModel.getColumn(2).cellEditor = ValueEditor()
            // Col 3: Secret
            columnModel.getColumn(3).apply { preferredWidth = 28; maxWidth = 28; minWidth = 28 }
            columnModel.getColumn(3).cellRenderer = SecretRenderer()

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val row = rowAtPoint(e.point)
                    val col = columnAtPoint(e.point)
                    if (row !in 0 until model.rowCount) return
                    when (col) {
                        0 -> model.setValueAt(!(model.getValueAt(row, 0) as Boolean), row, 0)
                        3 -> this@VariablesTablePanel.model.toggleSecret(row, !this@VariablesTablePanel.model.isSecret(row))
                    }
                }
            })
        }

        model.addTableModelListener {
            if (!suppressChanges) onChange?.invoke(model.getVariables())
        }

        val addBtn = JButton(AllIcons.General.Add).apply {
            isBorderPainted = false; isContentAreaFilled = false
            addActionListener {
                this@VariablesTablePanel.model.addRow()
                table.editCellAt(this@VariablesTablePanel.model.rowCount - 1, 1)
                table.transferFocus()
            }
        }
        val removeBtn = JButton(AllIcons.General.Remove).apply {
            isBorderPainted = false; isContentAreaFilled = false
            addActionListener {
                val row = table.selectedRow
                if (row >= 0) this@VariablesTablePanel.model.removeRow(row)
            }
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        toolbar.add(addBtn); toolbar.add(removeBtn)
        toolbar.border = JBUI.Borders.customLineBottom(JBColor.border())

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
    }

    fun setVariables(vars: List<VariableEntry>) {
        suppressChanges = true
        model.setVariables(vars)
        suppressChanges = false
    }

    // ── Model ─────────────────────────────────────────────────────────────────

    private inner class VariablesModel : AbstractTableModel() {
        private val rows = mutableListOf<VariableEntry>()
        private val cols = arrayOf("", "Key", "Value", "")

        fun setVariables(vars: List<VariableEntry>) {
            rows.clear(); rows.addAll(vars); fireTableDataChanged()
        }

        fun getVariables(): List<VariableEntry> = rows.toList()

        fun isSecret(row: Int): Boolean = rows.getOrNull(row)?.isSecret ?: false
        fun getKey(row: Int): String = rows.getOrNull(row)?.key ?: ""

        fun addRow() {
            rows.add(VariableEntry())
            fireTableRowsInserted(rows.size - 1, rows.size - 1)
        }

        fun removeRow(idx: Int) {
            if (idx !in rows.indices) return
            val entry = rows[idx]
            if (entry.isSecret && entry.key.isNotEmpty()) {
                SecretStorageService.remove(projectHash, entry.key)
            }
            rows.removeAt(idx)
            fireTableRowsDeleted(idx, idx)
        }

        fun toggleSecret(row: Int, makeSecret: Boolean) {
            if (row !in rows.indices) return
            val entry = rows[row]
            if (makeSecret && !entry.isSecret) {
                if (entry.key.isNotEmpty()) SecretStorageService.set(projectHash, entry.key, entry.value)
                rows[row] = entry.copy(isSecret = true, value = "")
            } else if (!makeSecret && entry.isSecret) {
                val value = if (entry.key.isNotEmpty()) SecretStorageService.get(projectHash, entry.key) ?: "" else ""
                if (entry.key.isNotEmpty()) SecretStorageService.remove(projectHash, entry.key)
                rows[row] = entry.copy(isSecret = false, value = value)
            }
            fireTableRowsUpdated(row, row)
        }

        override fun getRowCount() = rows.size
        override fun getColumnCount() = 4
        override fun getColumnName(col: Int) = cols[col]
        override fun isCellEditable(row: Int, col: Int) = col == 1 || col == 2
        override fun getColumnClass(col: Int) =
            if (col == 0) java.lang.Boolean::class.java else String::class.java

        override fun getValueAt(row: Int, col: Int): Any = when (col) {
            0 -> rows[row].enabled
            1 -> rows[row].key
            2 -> if (rows[row].isSecret) "" else rows[row].value
            3 -> rows[row].isSecret
            else -> ""
        }

        override fun setValueAt(value: Any?, row: Int, col: Int) {
            if (row !in rows.indices) return
            when (col) {
                0 -> rows[row] = rows[row].copy(enabled = value as Boolean)
                1 -> {
                    val oldKey = rows[row].key
                    val newKey = value as? String ?: ""
                    if (rows[row].isSecret && oldKey.isNotEmpty() && newKey != oldKey) {
                        val secret = SecretStorageService.get(projectHash, oldKey) ?: ""
                        if (newKey.isNotEmpty()) SecretStorageService.set(projectHash, newKey, secret)
                        SecretStorageService.remove(projectHash, oldKey)
                    }
                    var updated = rows[row].copy(key = newKey)
                    if (!updated.isSecret && newKey.matches(SECRET_PATTERN)) {
                        if (newKey.isNotEmpty()) SecretStorageService.set(projectHash, newKey, updated.value)
                        updated = updated.copy(isSecret = true, value = "")
                    }
                    rows[row] = updated
                }
                2 -> {
                    val newValue = value as? String ?: ""
                    if (rows[row].isSecret) {
                        if (rows[row].key.isNotEmpty()) SecretStorageService.set(projectHash, rows[row].key, newValue)
                        rows[row] = rows[row].copy(value = "")
                    } else {
                        rows[row] = rows[row].copy(value = newValue)
                    }
                }
            }
            fireTableCellUpdated(row, col)
        }
    }

    // ── Value renderer ─────────────────────────────────────────────────────────

    private inner class ValueRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val display = if (model.isSecret(row)) "••••••••" else value as? String ?: ""
            return super.getTableCellRendererComponent(table, display, isSelected, hasFocus, row, column)
        }
    }

    // ── Secret column renderer ─────────────────────────────────────────────────

    private class SecretRenderer : DefaultTableCellRenderer() {
        init { horizontalAlignment = SwingConstants.CENTER }

        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val label = super.getTableCellRendererComponent(table, null, isSelected, hasFocus, row, column) as JLabel
            label.icon = if (value as? Boolean == true) AllIcons.Ide.Readonly else AllIcons.Ide.Readwrite
            label.text = null
            label.toolTipText = if (value as? Boolean == true) "Secret (stored in OS keychain)" else "Click to mark as secret"
            return label
        }
    }

    // ── Value cell editor ──────────────────────────────────────────────────────

    private inner class ValueEditor : AbstractCellEditor(), TableCellEditor {
        private val textField = JTextField()
        private val passwordField = JPasswordField()
        private var editingSecret = false
        private var revealed = false

        private val toggleBtn = JButton(AllIcons.General.Show).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = java.awt.Dimension(22, 22)
            toolTipText = "Show / hide value"
            addActionListener {
                revealed = !revealed
                passwordField.echoChar = if (revealed) ' ' else '•'
                icon = if (revealed) AllIcons.General.HideToolWindow else AllIcons.General.Show
            }
        }

        private val wrapper = JPanel(BorderLayout()).apply {
            add(passwordField, BorderLayout.CENTER)
            add(toggleBtn, BorderLayout.EAST)
        }

        override fun getCellEditorValue(): Any =
            if (editingSecret) String(passwordField.password) else textField.text

        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
        ): Component {
            editingSecret = model.isSecret(row)
            return if (editingSecret) {
                revealed = false
                passwordField.echoChar = '•'
                toggleBtn.icon = AllIcons.Actions.Show
                val stored = if (model.getKey(row).isNotEmpty())
                    SecretStorageService.get(projectHash, model.getKey(row)) ?: "" else ""
                passwordField.text = stored
                wrapper
            } else {
                textField.text = value as? String ?: ""
                textField
            }
        }
    }

    companion object {
        val SECRET_PATTERN = Regex("""(?i).*(token|key|password|secret|pass|credential|cert|private).*""")
    }
}
