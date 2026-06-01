package com.sonarwhale.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionToolbar
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.license.LicenseService
import com.sonarwhale.license.LicenseStatus
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.service.RouteIndexService
import com.sonarwhale.settings.SonarwhaleConfigurable
import java.awt.BorderLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class SonarwhalePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val endpointTree = EndpointTree(project)
    private val detailPanel  = DetailPanel(project)
    private val progressBar  = JProgressBar().also {
        it.isIndeterminate = true
        it.isVisible = false
    }

    // ── Search bar components ─────────────────────────────────────────────────

    private val searchTextField = object : SearchTextField() {
        // Prevent SearchTextField from consuming Escape so our key listener can close the bar.
        override fun toClearTextOnEscape() = false
    }

    private val matchCountLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
        font       = font.deriveFont(11f)
        border     = JBUI.Borders.empty(0, 6, 0, 2)
    }

    private val prevMatchBtn = JButton(AllIcons.Actions.PreviousOccurence).apply {
        isBorderPainted     = false
        isContentAreaFilled = false
        toolTipText         = "Previous match (Shift+Enter)"
        addActionListener   { endpointTree.navigateMatch(forward = false) }
    }

    private val nextMatchBtn = JButton(AllIcons.Actions.NextOccurence).apply {
        isBorderPainted     = false
        isContentAreaFilled = false
        toolTipText         = "Next match (Enter)"
        addActionListener   { endpointTree.navigateMatch(forward = true) }
    }

    /**
     * Search bar: [🔍 icon] [text field fills] [X/Y counter] [↑] [↓]
     * Hidden by default, shown when the Find toggle is pressed or Ctrl+F is pressed.
     */
    private val searchBarPanel = JPanel(BorderLayout(4, 0)).also { bar ->
        bar.border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(3, 6)
        )
        val rightPanel = JPanel().apply {
            layout   = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(matchCountLabel)
            add(prevMatchBtn)
            add(nextMatchBtn)
        }
        bar.add(searchTextField, BorderLayout.CENTER)
        bar.add(rightPanel,      BorderLayout.EAST)
        bar.isVisible = false
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private var allEndpoints: List<ApiEndpoint> = emptyList()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        val service = RouteIndexService.getInstance(project)

        // ── Rechte Toolbar: Settings ──────────────────────────────────────────
        val rightGroup = DefaultActionGroup()

        rightGroup.add(object : AnAction(
            "Sonarwhale Settings",
            "Open Sonarwhale settings",
            AllIcons.General.Settings
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, SonarwhaleConfigurable::class.java)
            }
            override fun getActionUpdateThread() =
                com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
        })

        val rightToolbar = ActionManager.getInstance()
            .createActionToolbar("Sonarwhale.RightToolbar", rightGroup, true)
        rightToolbar.targetComponent = this

        val licenseStatus = LicenseService.getInstance().getStatus()
        val badgeLabel = JBLabel(if (licenseStatus == LicenseStatus.TRIAL) "TRIAL" else "FREE").apply {
            font        = font.deriveFont(Font.BOLD, 10f)
            foreground  = JBColor.GRAY
            border      = JBUI.Borders.empty(0, 0, 0, 4)
            isVisible   = licenseStatus != LicenseStatus.PREMIUM
            toolTipText = "Upgrade to Sonarwhale Premium"
            cursor      = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) =
                    LicenseService.requestLicense()
            })
        }

        val rightPanel = JPanel(BorderLayout()).apply { isOpaque = false }
        rightPanel.add(badgeLabel,            BorderLayout.WEST)
        rightPanel.add(rightToolbar.component, BorderLayout.EAST)

        val topBar = JPanel(BorderLayout(4, 0))
        topBar.border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(4, 4)
        )
        topBar.add(buildToolbar().component, BorderLayout.WEST)
        topBar.add(rightPanel,               BorderLayout.EAST)

        // topBar (always visible) + collapsible search bar stacked in NORTH
        val leftTop = JPanel(BorderLayout())
        leftTop.add(topBar,         BorderLayout.NORTH)
        leftTop.add(searchBarPanel, BorderLayout.CENTER)

        val leftPanel = JPanel(BorderLayout())
        leftPanel.add(leftTop,                    BorderLayout.NORTH)
        leftPanel.add(progressBar,                BorderLayout.SOUTH)
        leftPanel.add(JBScrollPane(endpointTree), BorderLayout.CENTER)

        val splitter = OnePixelSplitter(false, 0.20f)
        splitter.firstComponent  = leftPanel
        splitter.secondComponent = detailPanel
        add(splitter, BorderLayout.CENTER)

        // ── Search wiring ──────────────────────────────────────────────────

        endpointTree.onToggleSearch = { toggleSearch() }

        endpointTree.onMatchCountChanged = { current, total ->
            matchCountLabel.text = if (total == 0) "" else "$current/$total"
        }

        // Keyboard shortcuts while the search field has focus
        searchTextField.addKeyboardListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when {
                    e.keyCode == KeyEvent.VK_ESCAPE                 -> { if (searchBarPanel.isVisible) toggleSearch() }
                    e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> { endpointTree.navigateMatch(false); e.consume() }
                    e.keyCode == KeyEvent.VK_ENTER                  -> { endpointTree.navigateMatch(true);  e.consume() }
                    e.keyCode == KeyEvent.VK_UP                     -> { endpointTree.navigateMatch(false); e.consume() }
                    e.keyCode == KeyEvent.VK_DOWN                   -> { endpointTree.navigateMatch(true);  e.consume() }
                }
            }
        })

        searchTextField.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  = updateQuery()
            override fun removeUpdate(e: DocumentEvent)  = updateQuery()
            override fun changedUpdate(e: DocumentEvent) = updateQuery()
        })

        // ── Tree callbacks ─────────────────────────────────────────────────

        endpointTree.onEndpointSelected = { endpoint ->
            service.setCurrentEndpoint(endpoint.id)
            detailPanel.showEndpoint(endpoint)
        }

        endpointTree.onControllerSelected = { node ->
            service.setCurrentEndpoint(null)
            detailPanel.showController(node)
        }

        endpointTree.onGlobalSelected = {
            service.setCurrentEndpoint(null)
            detailPanel.showGlobal()
        }

        endpointTree.onCollectionSelected = { collection ->
            detailPanel.showCollection(collection)
        }

        endpointTree.onRequestSelected = { endpoint, request ->
            service.setCurrentEndpoint(endpoint.id)
            detailPanel.showRequest(endpoint, request)
        }

        detailPanel.requestPanel.onRequestSaved = {
            val epId = detailPanel.requestPanel.currentEndpointId
            if (epId != null) endpointTree.refreshRequests(epId)
        }

        detailPanel.requestPanel.onRequestDuplicated = { endpoint, req ->
            endpointTree.refreshRequests(endpoint.id)
            endpointTree.selectRequest(endpoint.id, req.id)
            service.setCurrentEndpoint(endpoint.id)
            detailPanel.showRequest(endpoint, req)
        }

        // ── Service listeners ──────────────────────────────────────────────

        service.addSelectionListener { id ->
            endpointTree.selectEndpoint(id)
        }

        service.addRunRequestListener { endpointId, requestId ->
            val endpoint = service.endpoints.firstOrNull { it.id == endpointId } ?: return@addRunRequestListener
            val request  = SonarwhaleStateService.getInstance(project).getRequest(endpointId, requestId)
            if (request != null) {
                val toolWindowVisible = ToolWindowManager.getInstance(project)
                    .getToolWindow("Sonarwhale")?.isVisible == true
                endpointTree.selectRequest(endpointId, requestId)
                detailPanel.showRequest(endpoint, request)
                if (!toolWindowVisible) {
                    detailPanel.requestPanel.onNextResponse = { status, _, _, _ ->
                        val (type, emoji) = when {
                            status in 200..299 -> NotificationType.INFORMATION to "✓"
                            status == 0        -> NotificationType.ERROR        to "✗"
                            else               -> NotificationType.WARNING      to "!"
                        }
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Sonarwhale")
                            .createNotification("$emoji ${endpoint.method.name} ${endpoint.path} — $status", type)
                            .addAction(NotificationAction.createSimpleExpiring("Show response") {
                                ToolWindowManager.getInstance(project).getToolWindow("Sonarwhale")?.show(null)
                            })
                            .notify(project)
                    }
                }
                detailPanel.requestPanel.triggerSend()
            } else {
                endpointTree.selectEndpoint(endpointId)
                detailPanel.showEndpoint(endpoint)
            }
        }

        service.addLoadingListener { loading -> progressBar.isVisible = loading }

        service.addListener { endpoints ->
            allEndpoints = endpoints
            endpointTree.updateEndpoints(allEndpoints)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun updateEndpoints(endpoints: List<ApiEndpoint>) {
        allEndpoints = endpoints
        endpointTree.updateEndpoints(allEndpoints)
    }

    fun applyGeneralSettings() = detailPanel.applyGeneralSettings()

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Shows or hides the search bar, focusing / clearing as appropriate. */
    private fun toggleSearch() {
        val show = !searchBarPanel.isVisible
        searchBarPanel.isVisible = show
        if (show) {
            searchTextField.requestFocusInWindow()
        } else {
            searchTextField.text     = ""
            endpointTree.searchQuery = ""
            matchCountLabel.text     = ""
        }
    }

    private fun updateQuery() {
        endpointTree.searchQuery = searchTextField.text.trim()
    }

    private fun buildToolbar(): ActionToolbar {
        val group = DefaultActionGroup()

        group.add(object : AnAction("Refresh", "Re-scan all OpenAPI sources", AllIcons.Actions.Refresh) {
            override fun actionPerformed(e: AnActionEvent) {
                RouteIndexService.getInstance(project).refresh()
            }
        })

        group.addSeparator()

        group.add(object : ToggleAction("Find", "Show / hide search bar (Ctrl+F)", AllIcons.Actions.Find) {
            override fun isSelected(e: AnActionEvent): Boolean = searchBarPanel.isVisible
            override fun setSelected(e: AnActionEvent, state: Boolean) = toggleSearch()
        })

        group.add(object : AnAction("Expand Selected", "Expand selected node and all its children", AllIcons.Actions.Expandall) {
            override fun actionPerformed(e: AnActionEvent) = endpointTree.expandSelected()
        })

        group.add(object : AnAction("Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall) {
            override fun actionPerformed(e: AnActionEvent) = endpointTree.collapseAll()
        })

        val toolbar = ActionManager.getInstance().createActionToolbar("Sonarwhale.Toolbar", group, true)
        toolbar.targetComponent = this
        return toolbar
    }

}
