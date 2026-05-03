package com.sonarwhale.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.sonarwhale.model.ApiCollection
import com.sonarwhale.model.CollectionEnvironment
import com.sonarwhale.model.EnvironmentSource
import com.sonarwhale.service.CollectionService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.util.UUID
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel

/**
 * Settings page for managing collections and their OpenAPI sources.
 * One collection = one API source (ServerUrl, FilePath, or StaticImport).
 * Environments (variable sets) are managed in the tool window.
 */
class SonarwhaleSourcesConfigurable(private val project: Project) : Configurable {

    private val service: CollectionService get() = CollectionService.getInstance(project)

    // Local working copy — changes committed only on apply()
    private var collections: MutableList<ApiCollection> = mutableListOf()
    private var selectedIdx = -1
    private var modified = false
    private var suppressListener = false

    private val listModel = DefaultListModel<String>()
    private val collectionList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    // Detail fields
    private val nameField       = JTextField()
    private val radioServer     = JRadioButton("Server URL")
    private val radioFile       = JRadioButton("File path")
    private val radioStatic     = JRadioButton("Static JSON")
    @Suppress("UNUSED_VARIABLE")
    private val sourceGroup     = ButtonGroup().also { it.add(radioServer); it.add(radioFile); it.add(radioStatic) }
    private val sourceCardLayout = CardLayout()
    private val sourceCards     = JPanel(sourceCardLayout)

    private val hostField       = JTextField("http://localhost")
    private val portSpinner     = JSpinner(SpinnerNumberModel(5000, 1, 65535, 1))
    private val pathField       = JTextField().also { it.toolTipText = "Leave empty for auto-discovery" }
    private val filePathField   = TextFieldWithBrowseButton()
    private val staticArea      = JTextArea(10, 40).also {
        it.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        it.lineWrap = false
        it.toolTipText = "Paste your OpenAPI JSON here"
    }

    override fun getDisplayName() = "Sources"

    override fun createComponent(): JComponent {
        reset()

        filePathField.addBrowseFolderListener(
            "Select OpenAPI file", "Choose a swagger.json or openapi.yaml file", project,
            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                .withFileFilter { it.extension?.lowercase() in listOf("json", "yaml", "yml") }
        )

        buildSourceCards()

        collectionList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !suppressListener) {
                saveCurrentToCollection()
                selectedIdx = collectionList.selectedIndex
                loadCollection()
            }
        }

        radioServer.addActionListener { sourceCardLayout.show(sourceCards, "server"); modified = true }
        radioFile.addActionListener   { sourceCardLayout.show(sourceCards, "file");   modified = true }
        radioStatic.addActionListener { sourceCardLayout.show(sourceCards, "static"); modified = true }

        val root = JPanel(BorderLayout(8, 0))
        root.add(buildListPanel(), BorderLayout.WEST)
        root.add(buildDetailPanel(), BorderLayout.CENTER)
        return root
    }

    override fun isModified() = modified

    override fun apply() {
        saveCurrentToCollection()
        val serviceIds = service.getAll().map { it.id }.toSet()
        for (col in collections) {
            if (col.id in serviceIds) service.update(col) else service.add(col)
        }
        serviceIds.filter { id -> collections.none { it.id == id } }.forEach { service.remove(it) }
        modified = false
    }

    override fun reset() {
        collections = service.getAll().toMutableList()
        suppressListener = true
        listModel.clear()
        collections.forEach { listModel.addElement(it.name) }
        selectedIdx = if (collections.isNotEmpty()) 0 else -1
        if (selectedIdx >= 0) {
            collectionList.selectedIndex = selectedIdx
            loadCollection()
        } else {
            clearDetail()
        }
        suppressListener = false
        modified = false
    }

    // ── List panel ────────────────────────────────────────────────────────────

    private fun buildListPanel(): JPanel {
        val addBtn = JButton(AllIcons.General.Add).apply {
            isBorderPainted = false; isContentAreaFilled = false
            toolTipText = "Add collection"
            addActionListener { addCollection() }
        }
        val removeBtn = JButton(AllIcons.General.Remove).apply {
            isBorderPainted = false; isContentAreaFilled = false
            toolTipText = "Remove collection"
            addActionListener { removeCollection() }
        }
        val toolbar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 2)).also {
            it.isOpaque = false; it.add(addBtn); it.add(removeBtn)
        }

        val panel = JPanel(BorderLayout())
        panel.add(toolbar, BorderLayout.NORTH)
        panel.add(JScrollPane(collectionList), BorderLayout.CENTER)
        panel.preferredSize = JBUI.size(180, 360)
        return panel
    }

    private fun addCollection() {
        val name = JOptionPane.showInputDialog(
            collectionList, "Collection name:", "New Collection", JOptionPane.PLAIN_MESSAGE
        )?.trim() ?: return
        if (name.isEmpty()) return
        saveCurrentToCollection()
        val col = ApiCollection(
            name = name,
            environments = listOf(CollectionEnvironment(
                id = UUID.randomUUID().toString(),
                name = "default",
                source = EnvironmentSource.ServerUrl("http://localhost", 5000)
            ))
        )
        collections.add(col)
        listModel.addElement(col.name)
        suppressListener = true
        collectionList.selectedIndex = collections.size - 1
        suppressListener = false
        selectedIdx = collections.size - 1
        loadCollection()
        modified = true
    }

    private fun removeCollection() {
        val idx = collectionList.selectedIndex.takeIf { it >= 0 } ?: return
        val col = collections[idx]
        val confirm = JOptionPane.showConfirmDialog(
            collectionList, "Remove collection \"${col.name}\"?",
            "Remove Collection", JOptionPane.YES_NO_OPTION
        )
        if (confirm != JOptionPane.YES_OPTION) return
        collections.removeAt(idx)
        listModel.removeElementAt(idx)
        val newIdx = if (collections.isEmpty()) -1 else (idx - 1).coerceAtLeast(0)
        selectedIdx = newIdx
        suppressListener = true
        if (newIdx >= 0) collectionList.selectedIndex = newIdx
        suppressListener = false
        if (newIdx >= 0) loadCollection() else clearDetail()
        modified = true
    }

    // ── Detail panel ──────────────────────────────────────────────────────────

    private fun buildDetailPanel(): JPanel {
        val gbc = GridBagConstraints().also {
            it.fill = GridBagConstraints.HORIZONTAL; it.anchor = GridBagConstraints.WEST
            it.insets = Insets(3, 4, 3, 4)
        }
        val form = JPanel(GridBagLayout())
        form.border = JBUI.Borders.empty(4, 8)

        gbc.gridy = 0; gbc.gridx = 0; gbc.weightx = 0.0
        form.add(JBLabel("Name:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2
        form.add(nameField, gbc); gbc.gridwidth = 1

        gbc.gridy = 1; gbc.gridx = 0; gbc.weightx = 0.0
        form.add(JBLabel("Source type:"), gbc)
        val radioPanel = JPanel().also { p ->
            p.isOpaque = false; p.add(radioServer); p.add(radioFile); p.add(radioStatic)
        }
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.gridwidth = 2
        form.add(radioPanel, gbc); gbc.gridwidth = 1

        gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 3; gbc.weightx = 1.0
        form.add(sourceCards, gbc)

        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.customLineLeft(JBColor.border())
        panel.add(JBLabel("Configuration").apply {
            foreground = JBColor.GRAY; font = font.deriveFont(Font.PLAIN, 11f)
            border = JBUI.Borders.compound(
                JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 8))
        }, BorderLayout.NORTH)
        panel.add(form, BorderLayout.CENTER)
        return panel
    }

    private fun buildSourceCards() {
        // Server URL card
        val serverPanel = JPanel(GridBagLayout())
        serverPanel.border = JBUI.Borders.empty(6, 0)
        val sg = GridBagConstraints().also { it.fill = GridBagConstraints.HORIZONTAL; it.insets = Insets(2, 4, 2, 4) }
        sg.gridy = 0; sg.gridx = 0; sg.weightx = 0.0; serverPanel.add(JBLabel("Host:"), sg)
        sg.gridx = 1; sg.weightx = 1.0; serverPanel.add(hostField, sg)
        sg.gridy = 1; sg.gridx = 0; sg.weightx = 0.0; serverPanel.add(JBLabel("Port:"), sg)
        sg.gridx = 1; sg.weightx = 0.3
        portSpinner.preferredSize = JBUI.size(80, portSpinner.preferredSize.height)
        serverPanel.add(portSpinner, sg)
        sg.gridy = 2; sg.gridx = 0; sg.weightx = 0.0; serverPanel.add(JBLabel("OpenAPI path:"), sg)
        sg.gridx = 1; sg.weightx = 1.0; serverPanel.add(pathField, sg)
        sg.gridy = 3; sg.gridx = 0; sg.gridwidth = 2; sg.weightx = 1.0
        serverPanel.add(JBLabel("Leave path empty for auto-discovery.").apply {
            foreground = JBColor.GRAY; font = font.deriveFont(10f)
        }, sg)

        // File path card
        val filePanel = JPanel(GridBagLayout())
        filePanel.border = JBUI.Borders.empty(6, 0)
        val fg = GridBagConstraints().also { it.fill = GridBagConstraints.HORIZONTAL; it.insets = Insets(2, 4, 2, 4) }
        fg.gridy = 0; fg.gridx = 0; fg.weightx = 0.0; filePanel.add(JBLabel("File:"), fg)
        fg.gridx = 1; fg.weightx = 1.0; filePanel.add(filePathField, fg)
        fg.gridy = 1; fg.gridx = 0; fg.gridwidth = 2
        filePanel.add(JBLabel("Path to a local swagger.json or openapi.yaml file.").apply {
            foreground = JBColor.GRAY; font = font.deriveFont(10f)
        }, fg)

        // Static JSON card
        val staticPanel = JPanel(BorderLayout(0, 4))
        staticPanel.border = JBUI.Borders.empty(6, 4)
        staticPanel.add(JBLabel("Paste OpenAPI JSON:").apply {
            foreground = JBColor.GRAY; font = font.deriveFont(10f)
        }, BorderLayout.NORTH)
        staticPanel.add(JScrollPane(staticArea).also { it.preferredSize = JBUI.size(400, 160) }, BorderLayout.CENTER)

        sourceCards.add(serverPanel, "server")
        sourceCards.add(filePanel,   "file")
        sourceCards.add(staticPanel, "static")
    }

    // ── Load / save helpers ───────────────────────────────────────────────────

    private fun loadCollection() {
        val col = collections.getOrNull(selectedIdx) ?: run { clearDetail(); return }
        val source = col.environments.firstOrNull { it.id == col.activeEnvironmentId }?.source
            ?: col.environments.firstOrNull()?.source
            ?: EnvironmentSource.ServerUrl("http://localhost", 5000)
        nameField.text = col.name
        when (source) {
            is EnvironmentSource.ServerUrl -> {
                radioServer.isSelected = true; sourceCardLayout.show(sourceCards, "server")
                hostField.text = source.host; portSpinner.value = source.port
                pathField.text = source.openApiPath ?: ""
            }
            is EnvironmentSource.FilePath -> {
                radioFile.isSelected = true; sourceCardLayout.show(sourceCards, "file")
                filePathField.text = source.path
            }
            is EnvironmentSource.StaticImport -> {
                radioStatic.isSelected = true; sourceCardLayout.show(sourceCards, "static")
                staticArea.text = source.cachedContent
            }
        }
    }

    private fun saveCurrentToCollection() {
        val col = collections.getOrNull(selectedIdx) ?: return
        val name = nameField.text.trim().ifEmpty { col.name }
        val source: EnvironmentSource = when {
            radioServer.isSelected -> EnvironmentSource.ServerUrl(
                host = hostField.text.trim().ifEmpty { "http://localhost" },
                port = portSpinner.value as Int,
                openApiPath = pathField.text.trim().ifEmpty { null }
            )
            radioFile.isSelected -> EnvironmentSource.FilePath(path = filePathField.text.trim())
            else -> EnvironmentSource.StaticImport(cachedContent = staticArea.text.trim())
        }
        // Preserve all other environments; update only the active (primary) source env
        val envs = col.environments.toMutableList()
        val activeEnvIdx = envs.indexOfFirst { it.id == col.activeEnvironmentId }
            .takeIf { it >= 0 } ?: 0
        if (envs.isEmpty()) {
            envs.add(CollectionEnvironment(id = UUID.randomUUID().toString(), name = "default", source = source))
        } else {
            envs[activeEnvIdx] = envs[activeEnvIdx].copy(source = source)
        }
        if (listModel.size > selectedIdx && listModel.getElementAt(selectedIdx) != name) {
            listModel.setElementAt(name, selectedIdx)
            modified = true
        }
        collections[selectedIdx] = col.copy(name = name, environments = envs)
    }

    private fun clearDetail() {
        nameField.text = ""; hostField.text = "http://localhost"; portSpinner.value = 5000
        pathField.text = ""; filePathField.text = ""; staticArea.text = ""
        radioServer.isSelected = true; sourceCardLayout.show(sourceCards, "server")
    }
}
