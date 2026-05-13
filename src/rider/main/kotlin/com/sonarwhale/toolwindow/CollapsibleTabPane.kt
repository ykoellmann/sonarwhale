package com.sonarwhale.toolwindow

import com.intellij.ui.components.JBTabbedPane
import javax.swing.JComponent

/**
 * Drop-in replacement for the old collapsible tab pane — now simply delegates to JBTabbedPane
 * for the standard IntelliJ tab look. The collapse-on-click feature has been removed.
 */
class CollapsibleTabPane : JBTabbedPane() {

    /** Fired after each tab switch. [expanded] is always true (collapse removed). */
    var onTabChanged: ((name: String, expanded: Boolean) -> Unit)? = null

    init {
        addChangeListener {
            val idx = selectedIndex
            if (idx >= 0) onTabChanged?.invoke(getTitleAt(idx), true)
        }
    }

    /** Selects the tab with the given [name]. [isExpanded] is ignored (tabs are always visible). */
    fun restoreState(name: String?, isExpanded: Boolean) {
        if (name == null) return
        (0 until tabCount).firstOrNull { getTitleAt(it) == name }?.let { selectedIndex = it }
    }

    fun indexOfComponent(component: JComponent): Int =
        (0 until tabCount).firstOrNull { getComponentAt(it) == component } ?: -1
}
