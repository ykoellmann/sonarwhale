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
import com.sonarwhale.model.AuthConfig
import com.sonarwhale.model.AuthMode
import com.sonarwhale.model.CollectionEnvironment
import com.sonarwhale.model.EnvironmentSource
import com.sonarwhale.service.CollectionService
import com.sonarwhale.toolwindow.AuthConfigPanel
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

    private var collections: MutableList<ApiCollection> = mutableListOf()
    private var originalIds: Set<String> = emptySet()
    private var modified = false
    private var suppressingListener = false

    private val listModel = DefaultListModel<String>()
    private val collectionList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private val nameField        = JTextField()
    private val radioServer      = JRadioButton("Server URL")
    private val radioFile        = JRadioButton("File path")
    private val radioStatic      = JRadioButton("Static JSON")
    @Suppress("unused") // must be a field — ButtonGroup would be GC'd if local
    private val sourceGroup      = ButtonGroup().also { it.add(radioServer); it.add(radioFile); it.add(radioStatic) }
    private val sourceCardLayout = CardLayout()
    private val sourceCards      = JPanel(sourceCardLayout)

    private val hostField      = JTextField("http://localhost").also {
        it.toolTipText = "Host or IP — scheme optional, e.g. localhost or http://127.0.0.1"
    }
    private val portSpinner    = JSpinner(SpinnerNumberModel(5000, 1, 65535, 1))
    private val pathField      = JTextField().also { it.toolTipText = "Leave empty for auto-discovery" }
    private val filePathField  = TextFieldWithBrowseButton()
    private val staticArea     = JTextArea(10, 40).also {
        it.font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        it.lineWrap = false
        it.toolTipText = "Paste your OpenAPI JSON here"
    }

    // Auth panel for ServerUrl source — INHERIT is not meaningful here, default is NONE
    private val sourceAuthPanel = AuthConfigPanel(AuthConfig(mode = AuthMode.NONE)).apply {
        onChange = { modified = true }
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
            if (!e.valueIsAdjusting && !suppressingListener) {
                saveCurrentToCollection()
                loadCollection(collectionList.selectedIndex)
            }
        }

        addSourceTypeListener(radioServer, CARD_SERVER) { sourceAuthPanel.isVisible = true }
        addSourceTypeListener(radioFile,   CARD_FILE)   { sourceAuthPanel.isVisible = false }
        addSourceTypeListener(radioStatic, CARD_STATIC) { sourceAuthPanel.isVisible = false }

        val markModified = object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { modified = true }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { modified = true }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { modified = true }
        }
        nameField.document.addDocumentListener(markModified)
        hostField.document.addDocumentListener(markModified)
        pathField.document.addDocumentListener(markModified)
        filePathField.textField.document.addDocumentListener(markModified)
        portSpinner.addChangeListener { modified = true }
        staticArea.document.addDocumentListener(markModified)

        val root = JPanel(BorderLayout(8, 0))
        root.add(buildListPanel(), BorderLayout.WEST)
        root.add(buildDetailPanel(), BorderLayout.CENTER)
        return root
    }

    override fun isModified() = modified

    override fun apply() {
        saveCurrentToCollection()
        for (col in collections) {
            if (col.id in originalIds) service.update(col) else service.add(col)
        }
        originalIds.filter { id -> collections.none { it.id == id } }.forEach { service.remove(it) }
        originalIds = collections.map { it.id }.toSet()
        modified = false
    }

    override fun reset() {
        collections = service.getAll().toMutableList()
        originalIds = collections.map { it.id }.toSet()
        withSuppressedListener {
            listModel.clear()
            collections.forEach { listModel.addElement(it.name) }
            if (collections.isNotEmpty()) collectionList.selectedIndex = 0
        }
        loadCollection(collectionList.selectedIndex)
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
        withSuppressedListener { collectionList.selectedIndex = collections.size - 1 }
        loadCollection(collectionList.selectedIndex)
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
        withSuppressedListener { if (newIdx >= 0) collectionList.selectedIndex = newIdx }
        loadCollection(newIdx)
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

        gbc.gridy = 3; gbc.gridx = 0; gbc.gridwidth = 3; gbc.weightx = 1.0
        form.add(sourceAuthPanel, gbc)

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

        val filePanel = JPanel(GridBagLayout())
        filePanel.border = JBUI.Borders.empty(6, 0)
        val fg = GridBagConstraints().also { it.fill = GridBagConstraints.HORIZONTAL; it.insets = Insets(2, 4, 2, 4) }
        fg.gridy = 0; fg.gridx = 0; fg.weightx = 0.0; filePanel.add(JBLabel("File:"), fg)
        fg.gridx = 1; fg.weightx = 1.0; filePanel.add(filePathField, fg)
        fg.gridy = 1; fg.gridx = 0; fg.gridwidth = 2
        filePanel.add(JBLabel("Path to a local swagger.json or openapi.yaml file.").apply {
            foreground = JBColor.GRAY; font = font.deriveFont(10f)
        }, fg)

        val staticPanel = JPanel(BorderLayout(0, 4))
        staticPanel.border = JBUI.Borders.empty(6, 4)
        staticPanel.add(JBLabel("Paste OpenAPI JSON:").apply {
            foreground = JBColor.GRAY; font = font.deriveFont(10f)
        }, BorderLayout.NORTH)
        staticPanel.add(JScrollPane(staticArea).also { it.preferredSize = JBUI.size(400, 160) }, BorderLayout.CENTER)

        sourceCards.add(serverPanel, CARD_SERVER)
        sourceCards.add(filePanel,   CARD_FILE)
        sourceCards.add(staticPanel, CARD_STATIC)
    }

    // ── Load / save helpers ───────────────────────────────────────────────────

    private fun loadCollection(idx: Int) {
        val col = collections.getOrNull(idx) ?: run { clearDetail(); return }
        nameField.text = col.name
        val activeEnv = col.environments.firstOrNull { it.id == col.activeEnvironmentId }
            ?: col.environments.firstOrNull()
        when (val source = activeSourceOf(col)) {
            is EnvironmentSource.ServerUrl -> {
                radioServer.isSelected = true; sourceCardLayout.show(sourceCards, CARD_SERVER)
                hostField.text = source.host; portSpinner.value = source.port
                pathField.text = source.openApiPath ?: ""
                sourceAuthPanel.setAuth(activeEnv?.sourceAuth ?: AuthConfig(mode = AuthMode.NONE))
                sourceAuthPanel.isVisible = true
            }
            is EnvironmentSource.FilePath -> {
                radioFile.isSelected = true; sourceCardLayout.show(sourceCards, CARD_FILE)
                filePathField.text = source.path
                sourceAuthPanel.isVisible = false
            }
            is EnvironmentSource.StaticImport -> {
                radioStatic.isSelected = true; sourceCardLayout.show(sourceCards, CARD_STATIC)
                staticArea.text = source.cachedContent
                sourceAuthPanel.isVisible = false
            }
        }
    }

    private fun saveCurrentToCollection() {
        val idx = collectionList.selectedIndex.takeIf { it >= 0 } ?: return
        val col = collections.getOrNull(idx) ?: return
        val name = nameField.text.trim().ifEmpty { col.name }
        val source = buildSourceFromUI()
        val envs = col.environments.toMutableList()
        val activeEnvIdx = envs.indexOfFirst { it.id == col.activeEnvironmentId }.takeIf { it >= 0 } ?: 0
        val savedAuth = if (radioServer.isSelected) sourceAuthPanel.currentAuth else AuthConfig(mode = AuthMode.NONE)
        if (envs.isEmpty()) {
            envs.add(CollectionEnvironment(
                id = UUID.randomUUID().toString(),
                name = "default",
                source = source,
                sourceAuth = savedAuth
            ))
        } else {
            envs[activeEnvIdx] = envs[activeEnvIdx].copy(source = source, sourceAuth = savedAuth)
        }
        val updated = col.copy(name = name, environments = envs)
        if (updated != col) modified = true
        if (listModel.size > idx && listModel.getElementAt(idx) != name) listModel.setElementAt(name, idx)
        collections[idx] = updated
    }

    private fun buildSourceFromUI(): EnvironmentSource = when {
        radioServer.isSelected -> EnvironmentSource.ServerUrl(
            host = hostField.text.trim().ifEmpty { "http://localhost" },
            port = portSpinner.value as Int,
            openApiPath = pathField.text.trim().ifEmpty { null }
        )
        radioFile.isSelected -> EnvironmentSource.FilePath(path = filePathField.text.trim())
        else -> EnvironmentSource.StaticImport(cachedContent = staticArea.text.trim())
    }

    private fun activeSourceOf(col: ApiCollection): EnvironmentSource =
        col.environments.firstOrNull { it.id == col.activeEnvironmentId }?.source
            ?: col.environments.firstOrNull()?.source
            ?: EnvironmentSource.ServerUrl("http://localhost", 5000)

    private fun clearDetail() {
        nameField.text = ""; hostField.text = "http://localhost"; portSpinner.value = 5000
        pathField.text = ""; filePathField.text = ""; staticArea.text = ""
        radioServer.isSelected = true; sourceCardLayout.show(sourceCards, CARD_SERVER)
        sourceAuthPanel.setAuth(AuthConfig(mode = AuthMode.NONE))
        sourceAuthPanel.isVisible = true
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun withSuppressedListener(block: () -> Unit) {
        suppressingListener = true
        try { block() } finally { suppressingListener = false }
    }

    private fun addSourceTypeListener(radio: JRadioButton, card: String, onSelect: () -> Unit = {}) {
        radio.addActionListener { sourceCardLayout.show(sourceCards, card); onSelect(); modified = true }
    }

    companion object {
        private const val CARD_SERVER = "server"
        private const val CARD_FILE   = "file"
        private const val CARD_STATIC = "static"
    }
}
