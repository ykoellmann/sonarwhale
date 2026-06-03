package com.sonarwhale.license

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
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
                val actionManager = ActionManager.getInstance()
                // "RegisterPlugins" = open-source IDEs, "Register" = commercial IDEs (Rider, IDEA Ultimate, …)
                val action = actionManager.getAction("RegisterPlugins")
                    ?: actionManager.getAction("Register")
                if (action == null) {
                    BrowserUtil.browse(MARKETPLACE_URL)
                    return@invokeLater
                }
                val dataContext = DataContext { dataId ->
                    when (dataId) {
                        "register.product-descriptor.code" -> PRODUCT_CODE
                        "register.message"                 -> message
                        else                               -> null
                    }
                }
                ActionUtil.performAction(
                    action,
                    AnActionEvent.createEvent(dataContext, Presentation(), "", ActionUiKind.NONE, null)
                )
            }, ModalityState.nonModal())
        }
    }
}
