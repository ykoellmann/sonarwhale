package com.sonarwhale.license

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.ui.LicensingFacade
import java.time.Instant

enum class LicenseStatus { FREE, TRIAL, PREMIUM }

@Service(Service.Level.APP)
class LicenseService {

    @Volatile private var cachedStatus: LicenseStatus = LicenseStatus.FREE
    @Volatile private var lastCheck: Instant = Instant.EPOCH

fun getStatus(): LicenseStatus {
        val now = Instant.now()
        if (now.isBefore(lastCheck.plusSeconds(CACHE_TTL_SECONDS))) return cachedStatus

        val facade = LicensingFacade.getInstance() ?: return LicenseStatus.FREE

        val stamp = facade.getConfirmationStamp(PRODUCT_CODE)
        val status = when {
            stamp == null      -> LicenseStatus.FREE
            facade.isEvaluation -> LicenseStatus.TRIAL
            else               -> LicenseStatus.PREMIUM
        }

        cachedStatus = status
        lastCheck = now
        return status
    }

    val isPremium: Boolean get() = getStatus() != LicenseStatus.FREE

    fun isUnlocked(feature: PremiumFeature): Boolean = isPremium

    companion object {
        const val PRODUCT_CODE = "PSONARWHALE"
        private const val IS_MARKETPLACE_REGISTERED = true

        const val FREE_ENVIRONMENT_LIMIT = 1
        const val FREE_HISTORY_LIMIT = 10
        private const val CACHE_TTL_SECONDS = 3600L

        private const val MARKETPLACE_URL = "https://plugins.jetbrains.com/plugin/32058-sonarwhale"

        fun getInstance(): LicenseService = ApplicationManager.getApplication().service()

        /**
         * Opens the IDE's native license activation dialog (RegisterPlugins action) with
         * the plugin's product code pre-selected. Falls back to browser if the action is
         * unavailable (e.g. older platform builds).
         *
         * Pattern from JetBrains' official marketplace-makemecoffee-plugin example.
         */
        fun requestLicense(message: String = "Upgrade to Sonarwhale Premium to unlock all features.") {
            ApplicationManager.getApplication().invokeLater({
                if (!IS_MARKETPLACE_REGISTERED) {
                    BrowserUtil.browse(MARKETPLACE_URL)
                    return@invokeLater
                }
                val action = ActionManager.getInstance().getAction("RegisterPlugins")
                if (action == null) {
                    BrowserUtil.browse(MARKETPLACE_URL)
                    return@invokeLater
                }
                val dataContext = DataContext { dataId ->
                    when (dataId) {
                        "productCode" -> PRODUCT_CODE
                        "message"     -> message
                        else          -> null
                    }
                }
                val presentation = action.templatePresentation.clone()
                val event = AnActionEvent(
                    null, dataContext, ActionPlaces.UNKNOWN,
                    presentation, ActionManager.getInstance(), 0
                )
                action.actionPerformed(event)
            }, ModalityState.nonModal())
        }
    }
}
