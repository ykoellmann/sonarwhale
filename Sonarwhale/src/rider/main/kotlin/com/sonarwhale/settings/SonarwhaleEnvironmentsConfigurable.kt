package com.sonarwhale.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

class SonarwhaleEnvironmentsConfigurable(private val project: Project) : Configurable {

    override fun getDisplayName() = "Environments"

    override fun createComponent(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.add(
            JBLabel(
                "<html>Manage API collections and their environments directly in the " +
                "<b>Sonarwhale tool window</b>.<br>Select a collection node in the tree " +
                "to configure its sources, variables, and auth.</html>"
            ).apply { border = JBUI.Borders.empty(12) },
            BorderLayout.NORTH
        )
        return panel
    }

    override fun isModified() = false

    override fun apply() {}

    override fun reset() {}
}
