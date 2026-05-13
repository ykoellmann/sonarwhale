package com.sonarwhale.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.tree.ui.Control
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.gutter.SourceLocationService
import com.sonarwhale.model.ApiCollection
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.EndpointStatus
import com.sonarwhale.model.HttpMethod
import com.sonarwhale.model.SavedRequest
import com.sonarwhale.script.ScriptLevel
import com.sonarwhale.script.ScriptPhase
import com.sonarwhale.script.SonarwhaleScriptService
import com.sonarwhale.service.CollectionService
import java.awt.Color
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.UUID
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

// ── Node types ────────────────────────────────────────────────────────────────

class EndpointNode(val endpoint: ApiEndpoint) {
    override fun toString() = "${endpoint.method.name.padEnd(7)} ${endpoint.path}"
}

class RequestNode(val endpoint: ApiEndpoint, val request: SavedRequest) {
    override fun toString() = request.name
}

class ControllerNode(val name: String, val endpoints: List<ApiEndpoint>) {
    override fun toString() = name
}

object NoResults { override fun toString() = "No endpoints found" }

object GlobalNode {
    override fun toString() = "Global"
}

class CollectionNode(val collection: ApiCollection) {
    override fun toString() = collection.name
}

// ── Tree ──────────────────────────────────────────────────────────────────────

class EndpointTree(private val project: Project) : Tree() {

    // Callbacks wired by SonarwhalePanel
    var onEndpointSelected:    ((ApiEndpoint) -> Unit)?              = null
    var onControllerSelected:  ((ControllerNode) -> Unit)?           = null
    var onRequestSelected:     ((ApiEndpoint, SavedRequest) -> Unit)? = null
    var onGlobalSelected:      (() -> Unit)?                         = null
    var onCollectionSelected:  ((ApiCollection) -> Unit)?            = null

    private val stateService = SonarwhaleStateService.getInstance(project)

    // Last known endpoint list — needed to rebuild request sub-nodes without
    // losing the endpoint data.
    private var currentEndpoints: List<ApiEndpoint> = emptyList()

    // When true, the selection listener fires no callbacks. Used by refreshRequests
    // to silently restore the selection after nodeStructureChanged clears it,
    // preventing autoSave → onRequestSaved → refreshRequests feedback loops.
    private var suppressSelectionCallback = false

    init {
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        isRootVisible    = false
        showsRootHandles = true
        rowHeight        = JBUI.scale(20)
        cellRenderer     = EndpointTreeCellRenderer(project)
        // Use the COMPACT painter so DefaultTreeUI's getRowX uses minimal indent.
        // This is the correct per-tree API — UIManager and BasicTreeUI casts are both no-ops
        // because DefaultTreeUI delegates row-x entirely to a Control.Painter looked up from
        // this client property.
        putClientProperty(Control.Painter.KEY, Control.Painter.COMPACT)

        // Full-row selection: clicking anywhere on a row (not just the text) selects it
        putClientProperty("JTree.fullRowSelection", true)

        addTreeSelectionListener {
            if (suppressSelectionCallback) return@addTreeSelectionListener
            // Use selectionPath (current state) instead of e.path (the changed path).
            // When nodeStructureChanged clears the selection, e.path is the OLD (removed)
            // node, which would wrongly re-trigger the callback for the deleted item.
            val node = selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return@addTreeSelectionListener
            when (val obj = node.userObject) {
                is EndpointNode    -> onEndpointSelected?.invoke(obj.endpoint)
                is ControllerNode  -> onControllerSelected?.invoke(obj)
                is RequestNode     -> onRequestSelected?.invoke(obj.endpoint, obj.request)
                is GlobalNode      -> onGlobalSelected?.invoke()
                is CollectionNode  -> onCollectionSelected?.invoke(obj.collection)
            }
        }

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                handleMouse(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) handleMouse(e)
            }
        })

        // F4 → Jump to Source | Shift+F6 → Rename | Ctrl+D → Duplicate
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                when {
                    e.keyCode == KeyEvent.VK_F4 -> {
                        val epId = when (val obj = node.userObject) {
                            is EndpointNode -> obj.endpoint.id
                            is RequestNode  -> obj.endpoint.id
                            else            -> return
                        }
                        SourceLocationService.getInstance(project).navigate(epId)
                    }
                    e.keyCode == KeyEvent.VK_F6 && e.isShiftDown -> {
                        val obj = node.userObject as? RequestNode ?: return
                        performRename(obj.endpoint, obj.request)
                    }
                    e.keyCode == KeyEvent.VK_D && e.isControlDown -> {
                        val obj = node.userObject as? RequestNode ?: return
                        performDuplicate(obj.endpoint, obj.request)
                    }
                }
            }
        })
    }

    // ── Mouse handling ────────────────────────────────────────────────────────

    private fun handleMouse(e: MouseEvent) {
        // Select row on any click in the row area (full-width hit test)
        val row = rowForY(e.y)
        if (row >= 0) setSelectionRow(row)

        if (SwingUtilities.isRightMouseButton(e) || e.isPopupTrigger) {
            if (row >= 0) showContextMenu(e)
        }
    }

    /**
     * Returns the row index for a given Y coordinate, using full-width detection.
     * Unlike [getRowForLocation], this does not require the X to be over the text.
     */
    private fun rowForY(y: Int): Int {
        for (row in 0 until rowCount) {
            val bounds = getRowBounds(row) ?: continue
            if (y >= bounds.y && y < bounds.y + bounds.height) return row
        }
        return -1
    }

    // ── Context menu ──────────────────────────────────────────────────────────

    private fun showContextMenu(e: MouseEvent) {
        val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
        val group = DefaultActionGroup()

        when (val obj = node.userObject) {
            is GlobalNode      -> buildGlobalMenu(group)
            is CollectionNode  -> buildCollectionMenu(group, obj.collection)
            is ControllerNode  -> buildControllerMenu(group, obj)
            is EndpointNode    -> buildEndpointMenu(group, obj.endpoint)
            is RequestNode     -> buildRequestMenu(group, obj.endpoint, obj.request)
            else               -> return
        }

        val popup = ActionManager.getInstance()
            .createActionPopupMenu(ActionPlaces.POPUP, group)
        popup.component.show(e.component, e.x, e.y)
    }

    private fun addScriptPair(
        group: DefaultActionGroup,
        level: ScriptLevel,
        tag: String? = null,
        endpoint: ApiEndpoint? = null,
        request: SavedRequest? = null
    ) {
        val label = level.name.lowercase().replaceFirstChar { it.uppercase() }
        group.add(object : AnAction("Create Pre-Script ($label)", "", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                openOrCreateScriptInBackground(ScriptPhase.PRE, level, tag, endpoint, request)
            }
        })
        group.add(object : AnAction("Create Post-Script ($label)", "", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                openOrCreateScriptInBackground(ScriptPhase.POST, level, tag, endpoint, request)
            }
        })
    }

    private fun performRename(endpoint: ApiEndpoint, request: SavedRequest) {
        val newName = Messages.showInputDialog(
            project, "Request name:", "Rename Request", null, request.name, null
        )?.trim()?.takeIf { it.isNotBlank() } ?: return
        stateService.upsertRequest(endpoint.id, request.copy(name = newName))
        refreshRequests(endpoint.id)
        SwingUtilities.invokeLater { selectRequest(endpoint.id, request.id) }
    }

    fun performDuplicate(endpoint: ApiEndpoint, request: SavedRequest) {
        val existingNames = stateService.getRequests(endpoint.id).map { it.name }.toSet()
        val newName = nextDuplicateName(request.name, existingNames)
        val newReq = request.copy(id = UUID.randomUUID().toString(), name = newName, isDefault = false)
        stateService.upsertRequest(endpoint.id, newReq)
        refreshRequests(endpoint.id)
        SwingUtilities.invokeLater { selectRequest(endpoint.id, newReq.id) }
    }

    private fun addCopyPathAction(group: DefaultActionGroup, endpoint: ApiEndpoint) {
        group.add(object : AnAction("Copy Path", "Copy endpoint path to clipboard", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) {
                val cb = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                cb.setContents(java.awt.datatransfer.StringSelection(endpoint.path), null)
            }
        })
    }

    private fun openOrCreateScriptInBackground(
        phase: ScriptPhase,
        level: ScriptLevel,
        tag: String? = null,
        endpoint: ApiEndpoint? = null,
        request: SavedRequest? = null
    ) {
        val scriptService = SonarwhaleScriptService.getInstance(project)
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Creating script…", false) {
                override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                    val path = scriptService.getOrCreateScript(phase, level, tag, endpoint, request)
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        FileEditorManager.getInstance(project).openFile(vf, true)
                    }
                }
            }
        )
    }

    private fun buildGlobalMenu(group: DefaultActionGroup) {
        addScriptPair(group, ScriptLevel.GLOBAL)
    }

    private fun buildCollectionMenu(group: DefaultActionGroup, collection: ApiCollection) {
        val collectionService = CollectionService.getInstance(project)

        // Sub-menu: Switch Environment
        val envGroup = DefaultActionGroup("Switch Environment", true)
        for (env in collection.environments) {
            val isActive = env.id == collection.activeEnvironmentId
            envGroup.add(object : AnAction(
                if (isActive) "✓ ${env.name}" else env.name, "", null
            ) {
                override fun actionPerformed(e: AnActionEvent) {
                    collectionService.setActiveEnvironment(collection.id, env.id)
                    com.sonarwhale.service.RouteIndexService.getInstance(project).refresh()
                }
            })
        }
        group.add(envGroup)
        group.add(Separator.getInstance())

        addScriptPair(group, ScriptLevel.COLLECTION, tag = collection.id)
    }

    private fun buildControllerMenu(group: DefaultActionGroup, node: ControllerNode) {
        addScriptPair(group, ScriptLevel.TAG, tag = node.name)
    }

    private fun buildEndpointMenu(group: DefaultActionGroup, endpoint: ApiEndpoint) {
        val locService = SourceLocationService.getInstance(project)
        if (locService.canNavigate(endpoint.id)) {
            val jumpAction = object : AnAction("Jump to Source", "Open the controller method in the editor", AllIcons.Actions.EditSource) {
                override fun actionPerformed(e: AnActionEvent) { locService.navigate(endpoint.id) }
            }
            jumpAction.registerCustomShortcutSet(
                CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), null)), null)
            group.add(jumpAction)
            group.add(Separator.getInstance())
        }

        group.add(object : AnAction("New Request", "Create a new saved request for this endpoint", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                val name = Messages.showInputDialog(
                    project, "Request name:", "New Request", null
                )?.trim()?.takeIf { it.isNotBlank() } ?: return
                val isFirst = stateService.getRequests(endpoint.id).isEmpty()
                val req = SavedRequest(id = UUID.randomUUID().toString(), name = name, isDefault = isFirst)
                stateService.upsertRequest(endpoint.id, req)
                refreshRequests(endpoint.id)
                // Select the new request node
                val newNode = findRequestNode(endpoint.id, req.id)
                if (newNode != null) {
                    val path = javax.swing.tree.TreePath(newNode.path)
                    selectionPath = path
                    scrollPathToVisible(path)
                }
            }
        })

        group.add(Separator.getInstance())

        addCopyPathAction(group, endpoint)
        group.add(Separator.getInstance())
        addScriptPair(group, ScriptLevel.ENDPOINT,
            tag = endpoint.tags.firstOrNull() ?: "Default", endpoint = endpoint)
    }

    private fun buildRequestMenu(group: DefaultActionGroup, endpoint: ApiEndpoint, request: SavedRequest) {
        val locService = SourceLocationService.getInstance(project)
        if (locService.canNavigate(endpoint.id)) {
            val jumpAction = object : AnAction("Jump to Source", "Open the controller method in the editor", AllIcons.Actions.EditSource) {
                override fun actionPerformed(e: AnActionEvent) { locService.navigate(endpoint.id) }
            }
            jumpAction.registerCustomShortcutSet(
                CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), null)), null)
            group.add(jumpAction)
            group.add(Separator.getInstance())
        }

        val renameAction = object : AnAction("Rename…", "Rename this request", AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) { performRename(endpoint, request) }
        }
        renameAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_F6, InputEvent.SHIFT_DOWN_MASK), null)), null)
        group.add(renameAction)

        val dupAction = object : AnAction("Duplicate", "Duplicate this request", AllIcons.Actions.Copy) {
            override fun actionPerformed(e: AnActionEvent) { performDuplicate(endpoint, request) }
        }
        dupAction.registerCustomShortcutSet(
            CustomShortcutSet(KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), null)), null)
        group.add(dupAction)

        if (!request.isDefault) {
            group.add(object : AnAction("Set as Default", "Run this request via gutter icon", AllIcons.RunConfigurations.TestState.Run) {
                override fun actionPerformed(e: AnActionEvent) {
                    stateService.setDefault(endpoint.id, request.id)
                    refreshRequests(endpoint.id)
                }
            })
        }

        group.add(Separator.getInstance())

        group.add(object : AnAction("Delete", "Delete this request", AllIcons.General.Remove) {
            override fun actionPerformed(e: AnActionEvent) {
                val answer = Messages.showYesNoDialog(
                    project, "Delete request \"${request.name}\"?", "Delete Request", null
                )
                if (answer != Messages.YES) return
                stateService.removeRequest(endpoint.id, request.id)
                refreshRequests(endpoint.id)
                val epNode = findEndpointNode(endpoint.id)
                if (epNode != null) {
                    val path = javax.swing.tree.TreePath(epNode.path)
                    selectionPath = path
                    scrollPathToVisible(path)
                }
            }
        })

        group.add(Separator.getInstance())

        addCopyPathAction(group, endpoint)
        group.add(Separator.getInstance())
        addScriptPair(group, ScriptLevel.REQUEST,
            tag = endpoint.tags.firstOrNull() ?: "Default", endpoint = endpoint, request = request)
    }

    // ── Tree building ─────────────────────────────────────────────────────────

    fun updateEndpoints(endpoints: List<ApiEndpoint>) {
        currentEndpoints = endpoints
        val prevSelection = selectedPathObject()
        rebuildTree()
        restoreSelection(prevSelection)
    }

    /** Rebuilds only the request sub-nodes for one endpoint (e.g. after save). */
    fun refreshRequests(endpointId: String) {
        val prevSelected = selectedPathObject()
        val root = model?.root as? DefaultMutableTreeNode ?: return
        val treeModel = model as? DefaultTreeModel ?: return

        root.breadthFirstEnumeration().toList()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { (it.userObject as? EndpointNode)?.endpoint?.id == endpointId }
            ?.let { epNode ->
                epNode.removeAllChildren()
                val endpoint = currentEndpoints.firstOrNull { it.id == endpointId } ?: return@let
                stateService.getRequests(endpointId).forEach { req ->
                    epNode.add(DefaultMutableTreeNode(RequestNode(endpoint, req)))
                }
                treeModel.nodeStructureChanged(epNode)
                expandPath(javax.swing.tree.TreePath(epNode.path))

                // nodeStructureChanged clears the selection. Silently restore it so that
                // autoSave → onRequestSaved → refreshRequests doesn't wipe the highlight.
                // Callers that want to SELECT A DIFFERENT node do so afterwards explicitly.
                val prevReq = prevSelected as? RequestNode
                if (prevReq != null && prevReq.endpoint.id == endpointId) {
                    val restored = findRequestNode(endpointId, prevReq.request.id)
                    if (restored != null) {
                        suppressSelectionCallback = true
                        selectionPath = javax.swing.tree.TreePath(restored.path)
                        suppressSelectionCallback = false
                    }
                }
            }
    }

    private fun rebuildTree() {
        val root = DefaultMutableTreeNode("root")
        val globalNode = DefaultMutableTreeNode(GlobalNode)
        val collectionService = CollectionService.getInstance(project)

        val byCollection = currentEndpoints.groupBy { ep ->
            // endpointId format: "collectionId:METHOD /path"
            ep.id.substringBefore(":")
        }

        for (col in collectionService.getAll()) {
            val colNode = DefaultMutableTreeNode(CollectionNode(col))
            val eps = byCollection[col.id] ?: emptyList()
            if (eps.isEmpty()) {
                colNode.add(DefaultMutableTreeNode(NoResults))
            } else {
                val grouped = eps.groupBy { it.tags.firstOrNull() ?: "Endpoints" }
                for ((tag, tagEps) in grouped.entries.sortedBy { it.key }) {
                    val ctrlNode = DefaultMutableTreeNode(ControllerNode(tag, tagEps))
                    for (ep in tagEps.sortedWith(compareBy({ it.path }, { it.method.name }))) {
                        val epNode = DefaultMutableTreeNode(EndpointNode(ep))
                        stateService.getRequests(ep.id).forEach { req ->
                            epNode.add(DefaultMutableTreeNode(RequestNode(ep, req)))
                        }
                        ctrlNode.add(epNode)
                    }
                    colNode.add(ctrlNode)
                }
            }
            globalNode.add(colNode)
        }

        root.add(globalNode)
        model = DefaultTreeModel(root)
        expandAllRows()
    }

    // ── Selection helpers ─────────────────────────────────────────────────────

    fun selectEndpoint(id: String): Boolean {
        val node = findEndpointNode(id) ?: return false
        val path = javax.swing.tree.TreePath(node.path)
        selectionPath = path
        scrollPathToVisible(path)
        return true
    }

    fun selectRequest(endpointId: String, requestId: String): Boolean {
        val node = findRequestNode(endpointId, requestId) ?: return false
        val path = javax.swing.tree.TreePath(node.path)
        selectionPath = path
        scrollPathToVisible(path)
        return true
    }

    private fun findEndpointNode(endpointId: String): DefaultMutableTreeNode? {
        val root = model?.root as? DefaultMutableTreeNode ?: return null
        return root.breadthFirstEnumeration().toList()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull { (it.userObject as? EndpointNode)?.endpoint?.id == endpointId }
    }

    private fun findRequestNode(endpointId: String, requestId: String): DefaultMutableTreeNode? {
        val root = model?.root as? DefaultMutableTreeNode ?: return null
        return root.breadthFirstEnumeration().toList()
            .filterIsInstance<DefaultMutableTreeNode>()
            .firstOrNull {
                val obj = it.userObject as? RequestNode ?: return@firstOrNull false
                obj.endpoint.id == endpointId && obj.request.id == requestId
            }
    }

    private fun selectedPathObject(): Any? {
        val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return null
        return node.userObject
    }

    private fun restoreSelection(prev: Any?) {
        when (prev) {
            is EndpointNode -> selectEndpoint(prev.endpoint.id)
            is RequestNode  -> {
                val node = findRequestNode(prev.endpoint.id, prev.request.id) ?: return
                val path = javax.swing.tree.TreePath(node.path)
                selectionPath = path
            }
        }
    }

    private fun expandAllRows() {
        var i = 0
        while (i < rowCount) { expandRow(i); i++ }
    }
}

// ── Naming helpers ────────────────────────────────────────────────────────────

internal fun nextDuplicateName(name: String, existingNames: Set<String>): String {
    val base = name.replace(Regex("_\\d+$"), "")
    var i = 1
    while ("${base}_$i" in existingNames) i++
    return "${base}_$i"
}

// ── Shared UI helpers ─────────────────────────────────────────────────────────

internal fun methodColor(method: HttpMethod): Color = when (method) {
    HttpMethod.GET     -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x4C, 0xC4, 0x7F))
    HttpMethod.POST    -> JBColor(Color(0x00, 0x77, 0xDD), Color(0x44, 0x99, 0xFF))
    HttpMethod.PUT     -> JBColor(Color(0xCC, 0x66, 0x00), Color(0xFF, 0x99, 0x33))
    HttpMethod.DELETE  -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
    HttpMethod.PATCH   -> JBColor(Color(0x88, 0x00, 0xCC), Color(0xBB, 0x44, 0xFF))
    HttpMethod.HEAD    -> JBColor(Color(0x44, 0x44, 0x88), Color(0x88, 0x88, 0xCC))
    HttpMethod.OPTIONS -> JBColor(Color(0x44, 0x44, 0x44), Color(0x88, 0x88, 0x88))
}

// ── Cell renderer ─────────────────────────────────────────────────────────────

private class EndpointTreeCellRenderer(private val project: Project) : ColoredTreeCellRenderer() {

    private val countAttrs = SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor(Color(0x88, 0x88, 0x88), Color(0x77, 0x77, 0x77)))

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean,
        expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
    ) {
        clear()
        val node = value as? DefaultMutableTreeNode ?: return

        when (val obj = node.userObject) {
            is EndpointNode -> {
                val ep        = obj.endpoint
                val isRemoved = ep.status == EndpointStatus.REMOVED
                val styleFlag = if (isRemoved) SimpleTextAttributes.STYLE_STRIKEOUT or SimpleTextAttributes.STYLE_BOLD
                                else SimpleTextAttributes.STYLE_BOLD
                val methodColor = if (isRemoved) JBColor.GRAY else methodColor(ep.method)
                val pathAttr    = if (isRemoved)
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, JBColor.GRAY)
                else SimpleTextAttributes.REGULAR_ATTRIBUTES

                append(ep.method.name.padEnd(7), SimpleTextAttributes(styleFlag, methodColor))
                append(" ${ep.path}", pathAttr)
                ep.summary?.takeIf { it.isNotBlank() }?.let { s ->
                    val short = if (s.length > 40) s.take(40) + "…" else s
                    append("  $short", SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY))
                }
                if (node.childCount > 0) append("  ${node.childCount}", countAttrs)
                icon = null
            }
            is RequestNode -> {
                val isDefault = obj.request.isDefault
                append(
                    obj.request.name,
                    if (isDefault) SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, null)
                    else SimpleTextAttributes.REGULAR_ATTRIBUTES
                )
                if (isDefault) {
                    append("  default", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, JBColor.GRAY))
                }
                icon = null
            }
            is GlobalNode -> {
                append("Global", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                icon = AllIcons.Nodes.Package
            }
            is CollectionNode -> {
                val col = obj.collection
                val collectionService = CollectionService.getInstance(project)
                val activeEnvName = collectionService.getActiveEnvironment(col.id)?.name ?: "no env"
                append(col.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  $activeEnvName",
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY))
                val total = (0 until node.childCount).sumOf { i ->
                    ((node.getChildAt(i) as? DefaultMutableTreeNode)?.userObject as? ControllerNode)?.endpoints?.size ?: 0
                }
                if (total > 0) append("  $total", countAttrs)
                icon = AllIcons.Nodes.Folder
            }
            is ControllerNode -> {
                append(obj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                if (obj.endpoints.isNotEmpty()) append("  ${obj.endpoints.size}", countAttrs)
                icon = null
            }
            is NoResults -> append("No endpoints found", SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY))
            is String    -> append(obj, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        }
    }

}
