package com.sonarwhale.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.gutter.SonarwhaleGutterService
import com.sonarwhale.model.SonarwhaleGeneralSettings
import com.sonarwhale.service.RouteIndexService
import com.sonarwhale.toolwindow.SonarwhalePanel
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class SonarwhaleConfigurable(private val project: Project) : Configurable {

    // ── Widgets ───────────────────────────────────────────────────────────────

    private val gutterIconsCheck     = JBCheckBox("Show gutter icons next to API endpoint methods")
    private val autoFormatCheck      = JBCheckBox("Auto-format response body (JSON / XML)")
    private val followRedirectsCheck = JBCheckBox("Follow HTTP redirects")
    private val verifySslCheck       = JBCheckBox("Verify SSL certificates (disable for self-signed certs in dev)")

    private data class RefreshOption(val label: String, val seconds: Int) {
        override fun toString() = label
    }
    private val refreshOptions = arrayOf(
        RefreshOption("Disabled", 0),
        RefreshOption("30 seconds", 30),
        RefreshOption("1 minute", 60),
        RefreshOption("5 minutes", 300)
    )
    private val refreshCombo          = JComboBox(refreshOptions)
    private val timeoutSpinner        = JSpinner(SpinnerNumberModel(30, 1, 300, 1))
    private val defaultContentTypeField = JBTextField(20)

    // Cached on reset(); compared in isModified() to avoid re-parsing JSON on every IDE poll
    private var lastLoaded = SonarwhaleGeneralSettings()

    // ── Configurable ──────────────────────────────────────────────────────────

    override fun getDisplayName() = "Sonarwhale"

    override fun createComponent(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8, 12)

        val gbc = GridBagConstraints().apply {
            gridy = 0; anchor = GridBagConstraints.WEST; fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0; insets = Insets(0, 0, 2, 0)
        }

        fun addSection(title: String) {
            val sep = TitledSeparator(title)
            gbc.gridx = 0; gbc.gridwidth = 2
            gbc.insets = Insets(if (gbc.gridy == 0) 0 else 10, 0, 4, 0)
            panel.add(sep, gbc.clone() as GridBagConstraints)
            gbc.gridy++
            gbc.insets = Insets(0, 0, 2, 0)
        }

        fun addRow(label: String, widget: JComponent) {
            val labelGbc = gbc.clone() as GridBagConstraints
            labelGbc.gridx = 0; labelGbc.gridwidth = 1; labelGbc.weightx = 0.0
            labelGbc.fill = GridBagConstraints.NONE
            labelGbc.insets = Insets(2, 12, 2, 8)
            panel.add(JBLabel(label).apply { foreground = JBColor.foreground() }, labelGbc)

            val widgetGbc = gbc.clone() as GridBagConstraints
            widgetGbc.gridx = 1; widgetGbc.gridwidth = 1; widgetGbc.weightx = 1.0
            widgetGbc.fill = GridBagConstraints.NONE
            widgetGbc.insets = Insets(2, 0, 2, 0)
            panel.add(widget, widgetGbc)
            gbc.gridy++
        }

        fun addCheck(check: JBCheckBox) {
            val checkGbc = gbc.clone() as GridBagConstraints
            checkGbc.gridx = 0; checkGbc.gridwidth = 2; checkGbc.weightx = 1.0
            checkGbc.insets = Insets(2, 12, 2, 0)
            panel.add(check, checkGbc)
            gbc.gridy++
        }

        // ── Editor ────────────────────────────────────────────────────────────
        addSection("Editor")
        addCheck(gutterIconsCheck)

        // ── Network ───────────────────────────────────────────────────────────
        addSection("Network")
        addRow("Auto-refresh:", refreshCombo)
        addRow("Request timeout:", JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).also {
            it.isOpaque = false
            it.add(timeoutSpinner)
            it.add(JBLabel(" seconds").apply { border = JBUI.Borders.emptyLeft(4) })
        })
        addCheck(followRedirectsCheck)
        addCheck(verifySslCheck)

        // ── Requests ──────────────────────────────────────────────────────────
        addSection("Requests")
        addRow("Default Content-Type:", defaultContentTypeField)

        // ── Response ──────────────────────────────────────────────────────────
        addSection("Response")
        addCheck(autoFormatCheck)

        // push content to the top
        val fillerGbc = GridBagConstraints()
        fillerGbc.gridx = 0; fillerGbc.gridy = gbc.gridy; fillerGbc.gridwidth = 2
        fillerGbc.weighty = 1.0; fillerGbc.fill = GridBagConstraints.VERTICAL
        panel.add(JPanel().also { it.isOpaque = false }, fillerGbc)

        reset()
        return panel
    }

    override fun isModified(): Boolean = state() != lastLoaded

    override fun apply() {
        val s = state()
        lastLoaded = s
        SonarwhaleStateService.getInstance(project).setGeneralSettings(s)

        SonarwhaleGutterService.getInstance(project).applySettings()
        RouteIndexService.getInstance(project).restartIntervalRefresh()

        val tw = ToolWindowManager.getInstance(project).getToolWindow("Sonarwhale") ?: return
        (tw.contentManager.getContent(0)?.component as? SonarwhalePanel)?.applyGeneralSettings()
    }


    override fun reset() {
        val s = SonarwhaleStateService.getInstance(project).getGeneralSettings()
        lastLoaded = s
        gutterIconsCheck.isSelected      = s.gutterIconsEnabled
        autoFormatCheck.isSelected       = s.autoFormatResponse
        followRedirectsCheck.isSelected  = s.followRedirects
        verifySslCheck.isSelected        = s.verifySsl
        refreshCombo.selectedItem        = refreshOptions.firstOrNull { it.seconds == s.autoRefreshIntervalSeconds }
            ?: refreshOptions[2]
        timeoutSpinner.value             = s.requestTimeoutSeconds
        defaultContentTypeField.text     = s.defaultContentType
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun state() = SonarwhaleGeneralSettings(
        gutterIconsEnabled         = gutterIconsCheck.isSelected,
        autoFormatResponse         = autoFormatCheck.isSelected,
        followRedirects            = followRedirectsCheck.isSelected,
        verifySsl                  = verifySslCheck.isSelected,
        autoRefreshIntervalSeconds = (refreshCombo.selectedItem as? RefreshOption)?.seconds ?: 60,
        requestTimeoutSeconds      = (timeoutSpinner.value as? Int) ?: 30,
        defaultContentType         = defaultContentTypeField.text.trim().ifEmpty { "application/json" }
    )
}
