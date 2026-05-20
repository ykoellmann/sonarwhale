package com.sonarwhale.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.sonarwhale.service.SonarwhaleInitService
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Shown in the tool window when Sonarwhale has not yet been initialized for this project.
 * Displays a short explanation and an "Initialize Project" button.
 */
class SonarwhaleInitPanel(private val project: Project) : JPanel(GridBagLayout()) {

    init {
        val gbc = GridBagConstraints().apply {
            gridx = 0
            gridy = GridBagConstraints.RELATIVE
            anchor = GridBagConstraints.CENTER
            fill = GridBagConstraints.NONE
        }

        // Icon
        gbc.insets = Insets(0, 0, 12, 0)
        add(JBLabel(AllIcons.Nodes.Plugin), gbc)

        // Title
        gbc.insets = Insets(0, 0, 8, 0)
        add(JBLabel("Sonarwhale is not set up for this project").apply {
            font = font.deriveFont(Font.BOLD, 13f)
        }, gbc)

        // Description
        gbc.insets = Insets(0, 0, 6, 0)
        add(JBLabel("<html><center>This will create two directories in your project:</center></html>").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(12f)
        }, gbc)

        // Directory list
        gbc.insets = Insets(0, 0, 20, 0)
        add(JBLabel(
            "<html><center>" +
            "<code>.sonarwhale/</code> &nbsp; scripts, shareable via git<br/>" +
            "<code>.idea/sonarwhale/</code> &nbsp; local config, gitignored" +
            "</center></html>"
        ).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(11f)
            border = JBUI.Borders.empty(4, 16)
        }, gbc)

        // Initialize button
        gbc.insets = Insets(0, 0, 0, 0)
        add(JButton("Initialize Project").apply {
            addActionListener {
                isEnabled = false
                text = "Initializing…"
                SonarwhaleInitService.getInstance(project).initProject()
            }
        }, gbc)
    }
}
