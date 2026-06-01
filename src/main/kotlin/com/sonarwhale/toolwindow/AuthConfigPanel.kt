package com.sonarwhale.toolwindow

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.sonarwhale.license.LicenseService
import com.sonarwhale.license.PremiumFeature
import com.sonarwhale.license.PremiumGate
import com.sonarwhale.model.*
import java.awt.*
import javax.swing.*

/**
 * Auth configuration panel. Shows a mode dropdown and type-specific fields.
 * [inheritedMode] is the resolved auth mode from parent levels (shown as hint
 * when the current mode is INHERIT).
 */
class AuthConfigPanel(
    private var auth: AuthConfig = AuthConfig(),
    private var inheritedMode: AuthMode = AuthMode.NONE,
    var onChange: ((AuthConfig) -> Unit)? = null
) : JPanel(BorderLayout()) {

    private val modeCombo = JComboBox(AuthMode.values()).apply {
        selectedItem = auth.mode
    }
    private val fieldsPanel = object : JPanel(CardLayout()) {
        override fun getPreferredSize(): Dimension =
            components.firstOrNull { it.isVisible }?.preferredSize ?: Dimension(0, 0)
        override fun getMinimumSize(): Dimension = preferredSize
    }
    private val inheritHintLabel = JBLabel("").apply {
        font = font.deriveFont(Font.ITALIC, 10f)
        foreground = JBColor.GRAY
        border = JBUI.Borders.empty(0, 4)
    }

    val currentAuth: AuthConfig get() = buildFromFields()

    private var isLoading = false

    private data class AuthTemplate(val label: String, val config: AuthConfig)

    private val templates = listOf(
        AuthTemplate("Bearer — {{ACCESS_TOKEN}}",
            AuthConfig(mode = AuthMode.BEARER, bearerToken = "{{ACCESS_TOKEN}}")),
        AuthTemplate("JWT — {{JWT_TOKEN}}",
            AuthConfig(mode = AuthMode.BEARER, bearerToken = "{{JWT_TOKEN}}")),
        AuthTemplate("API Key in Header  (X-Api-Key: {{API_KEY}})",
            AuthConfig(mode = AuthMode.API_KEY, apiKeyName = "X-Api-Key", apiKeyValue = "{{API_KEY}}", apiKeyLocation = ApiKeyLocation.HEADER)),
        AuthTemplate("API Key in Query  (?api_key={{API_KEY}})",
            AuthConfig(mode = AuthMode.API_KEY, apiKeyName = "api_key", apiKeyValue = "{{API_KEY}}", apiKeyLocation = ApiKeyLocation.QUERY)),
        AuthTemplate("Basic — {{USERNAME}} / {{PASSWORD}}",
            AuthConfig(mode = AuthMode.BASIC, basicUsername = "{{USERNAME}}", basicPassword = "{{PASSWORD}}")),
        AuthTemplate("OAuth2 Client Credentials",
            AuthConfig(mode = AuthMode.OAUTH2_CLIENT_CREDENTIALS, oauthTokenUrl = "{{OAUTH_TOKEN_URL}}", oauthClientId = "{{CLIENT_ID}}", oauthClientSecret = "{{CLIENT_SECRET}}")),
    )

    // Field components per auth type
    private val bearerTokenField = JTextField(auth.bearerToken)
    private val basicUserField = JTextField(auth.basicUsername)
    private val basicPassField = JPasswordField(auth.basicPassword)
    private val apiKeyNameField = JTextField(auth.apiKeyName)
    private val apiKeyValueField = JTextField(auth.apiKeyValue)
    private val apiKeyLocationCombo = JComboBox(ApiKeyLocation.values()).apply {
        selectedItem = auth.apiKeyLocation
    }
    private val oauthTokenUrlField = JTextField(auth.oauthTokenUrl)
    private val oauthClientIdField = JTextField(auth.oauthClientId)
    private val oauthClientSecretField = JPasswordField(auth.oauthClientSecret)
    private val oauthScopeField = JTextField(auth.oauthScope)

    init {
        val templatesBtn = JButton("Templates").apply { font = font.deriveFont(10f) }
        PremiumGate.applyTo(templatesBtn, PremiumFeature.AUTH_HELPERS,
            locked = !LicenseService.getInstance().isUnlocked(PremiumFeature.AUTH_HELPERS))
        templatesBtn.addActionListener { showTemplatesPopup(templatesBtn) }

        val top = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        top.add(JBLabel("Auth:"))
        top.add(modeCombo)
        top.add(inheritHintLabel)
        top.add(templatesBtn)
        top.border = JBUI.Borders.empty(4)

        fieldsPanel.add(JPanel(), "INHERIT")
        fieldsPanel.add(JPanel(), "NONE")
        fieldsPanel.add(buildBearerPanel(), "BEARER")
        fieldsPanel.add(buildBasicPanel(), "BASIC")
        fieldsPanel.add(buildApiKeyPanel(), "API_KEY")
        fieldsPanel.add(buildOAuthPanel(), "OAUTH2_CLIENT_CREDENTIALS")

        add(top, BorderLayout.NORTH)
        add(fieldsPanel, BorderLayout.CENTER)

        modeCombo.addActionListener { onModeChanged() }
        listOf(bearerTokenField, basicUserField, basicPassField, apiKeyNameField,
            apiKeyValueField, oauthTokenUrlField, oauthClientIdField,
            oauthClientSecretField, oauthScopeField).forEach { field ->
            field.document.addDocumentListener(object :
                javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = emitChange()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = emitChange()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = emitChange()
            })
        }
        apiKeyLocationCombo.addActionListener { emitChange() }

        updateDisplay()
    }

    fun setAuth(newAuth: AuthConfig, newInherited: AuthMode = AuthMode.NONE) {
        isLoading = true
        try {
            auth = newAuth
            inheritedMode = newInherited
            modeCombo.selectedItem = auth.mode
            bearerTokenField.text = auth.bearerToken
            basicUserField.text = auth.basicUsername
            basicPassField.text = auth.basicPassword
            apiKeyNameField.text = auth.apiKeyName
            apiKeyValueField.text = auth.apiKeyValue
            apiKeyLocationCombo.selectedItem = auth.apiKeyLocation
            oauthTokenUrlField.text = auth.oauthTokenUrl
            oauthClientIdField.text = auth.oauthClientId
            oauthClientSecretField.text = auth.oauthClientSecret
            oauthScopeField.text = auth.oauthScope
            updateDisplay()
        } finally {
            isLoading = false
        }
    }

    private fun onModeChanged() {
        if (isLoading) return
        auth = buildFromFields()
        updateDisplay()
        onChange?.invoke(auth)
    }

    private fun emitChange() {
        if (isLoading) return
        auth = buildFromFields()
        onChange?.invoke(auth)
    }

    private fun buildFromFields(): AuthConfig {
        val mode = modeCombo.selectedItem as AuthMode
        return AuthConfig(
            mode = mode,
            bearerToken = bearerTokenField.text,
            basicUsername = basicUserField.text,
            basicPassword = String(basicPassField.password),
            apiKeyName = apiKeyNameField.text,
            apiKeyValue = apiKeyValueField.text,
            apiKeyLocation = apiKeyLocationCombo.selectedItem as ApiKeyLocation,
            oauthTokenUrl = oauthTokenUrlField.text,
            oauthClientId = oauthClientIdField.text,
            oauthClientSecret = String(oauthClientSecretField.password),
            oauthScope = oauthScopeField.text
        )
    }

    private fun updateDisplay() {
        val mode = modeCombo.selectedItem as AuthMode
        (fieldsPanel.layout as CardLayout).show(fieldsPanel, mode.name)
        inheritHintLabel.text = if (mode == AuthMode.INHERIT && inheritedMode != AuthMode.NONE)
            "(inherits ${inheritedMode.name} from parent)" else ""
        revalidate()
    }

    private fun buildBearerPanel() = formPanel(
        "Token" to bearerTokenField
    )

    private fun buildBasicPanel() = formPanel(
        "Username" to basicUserField,
        "Password" to basicPassField
    )

    private fun buildApiKeyPanel(): JPanel {
        val p = formPanel(
            "Key Name" to apiKeyNameField,
            "Key Value" to apiKeyValueField
        )
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        row.add(JBLabel("Add to:"))
        row.add(apiKeyLocationCombo)
        p.add(row)
        return p
    }

    private fun buildOAuthPanel() = formPanel(
        "Token URL" to oauthTokenUrlField,
        "Client ID" to oauthClientIdField,
        "Client Secret" to oauthClientSecretField,
        "Scope" to oauthScopeField
    )

    private fun showTemplatesPopup(anchor: JComponent) {
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(templates)
            .setTitle("Auth Templates")
            .setRenderer(SimpleListCellRenderer.create { label, t, _ -> label.text = t.label })
            .setItemChosenCallback { template ->
                setAuth(template.config, inheritedMode)
                onChange?.invoke(template.config)
            }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun formPanel(vararg rows: Pair<String, JComponent>): JPanel {
        val p = JPanel(GridBagLayout())
        p.border = JBUI.Borders.empty(8)
        val gbc = GridBagConstraints()
        rows.forEachIndexed { i, (label, field) ->
            gbc.gridy = i; gbc.gridx = 0; gbc.weightx = 0.0
            gbc.anchor = GridBagConstraints.WEST; gbc.insets = Insets(2, 0, 2, 8)
            p.add(JBLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 1.0; gbc.fill = GridBagConstraints.HORIZONTAL
            p.add(field, gbc)
        }
        // push content to top
        gbc.gridy = rows.size; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.VERTICAL
        p.add(JPanel(), gbc)
        return p
    }
}
