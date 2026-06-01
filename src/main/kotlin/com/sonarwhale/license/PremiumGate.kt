package com.sonarwhale.license

import com.intellij.openapi.util.IconLoader
import javax.swing.AbstractButton

object PremiumGate {

    private const val PREMIUM_TOOLTIP = "Available in Sonarwhale Premium"

    fun tooltipFor(feature: PremiumFeature): String = PREMIUM_TOOLTIP

    /**
     * Disables [button] and sets a premium tooltip when [locked].
     * Re-enables and clears the tooltip when not locked.
     */
    fun applyTo(button: AbstractButton, feature: PremiumFeature, locked: Boolean) {
        button.isEnabled = !locked
        button.toolTipText = if (locked) tooltipFor(feature) else null
        // Icon-only buttons with isContentAreaFilled=false don't auto-grey in IntelliJ LAF
        button.icon?.let { button.disabledIcon = if (locked) IconLoader.getDisabledIcon(it) else null }
    }
}
