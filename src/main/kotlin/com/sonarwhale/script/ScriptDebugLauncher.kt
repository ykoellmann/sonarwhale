package com.sonarwhale.script

import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.javascript.nodejs.debug.NodeDebugRunConfiguration
import com.intellij.openapi.project.Project

/**
 * Startet ein Sonarwhale pre/post-Skript im nativen IDE-Debugger.
 *
 * [SonarwhaleScriptRunProfile] implementiert [NodeDebugRunConfiguration] —
 * das ist das Interface das [NodeDebugProgramRunner.canRun] prüft:
 *   executorId == "Debug" && profile instanceof NodeDebugRunConfiguration
 * Der Runner übernimmt dann den gesamten CDP-Attach-Workflow automatisch.
 *
 * Muss auf dem EDT aufgerufen werden.
 */
object ScriptDebugLauncher {

    /**
     * Startet [scriptFile] im nativen JS-Debugger der IDE.
     * [onFinished] wird auf dem EDT aufgerufen sobald der Node.js-Prozess endet.
     */
    fun launch(
        project:    Project,
        scriptFile: ScriptFile,
        context:    ScriptContext,
        console:    ConsoleOutput,
        onFinished: (ScriptContext) -> Unit,
        notified:   java.util.concurrent.atomic.AtomicBoolean? = null
    ) {
        val executor   = DefaultDebugExecutor.getDebugExecutorInstance()
        val runProfile = SonarwhaleScriptRunProfile(scriptFile, project, context, console, onFinished)

        // ExecutionEnvironmentBuilder.create wirft ExecutionException wenn kein Runner gefunden wird
        // (z.B. in Community-Editionen ohne JavaScriptDebugger-Plugin). In dem Fall einmalig eine
        // Notification zeigen (notified verhindert Mehrfachanzeige pro Debug-Click) und onFinished
        // sofort aufrufen, damit der HTTP-Request trotzdem durchgeht.
        val env = try {
            ExecutionEnvironmentBuilder.create(project, executor, runProfile).build()
        } catch (_: ExecutionException) {
            val muted = com.sonarwhale.SonarwhaleStateService.getInstance(project)
                .getGeneralSettings().muteDebugUnavailableNotification
            if (!muted && (notified == null || notified.compareAndSet(false, true))) {
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("Sonarwhale")
                    .createNotification(
                        "Script debugging not available",
                        "The <b>JavaScript Debugger</b> plugin is required for script debugging " +
                            "(available in Rider, IntelliJ IDEA Ultimate, WebStorm). " +
                            "Scripts were skipped — request will be sent normally.",
                        com.intellij.notification.NotificationType.WARNING
                    )
                    .addAction(com.intellij.notification.NotificationAction.createSimple("Mute forever") {
                        val stateService = com.sonarwhale.SonarwhaleStateService.getInstance(project)
                        stateService.setGeneralSettings(
                            stateService.getGeneralSettings().copy(muteDebugUnavailableNotification = true)
                        )
                        com.intellij.notification.NotificationGroupManager.getInstance()
                            .getNotificationGroup("Sonarwhale")
                            .createNotification(
                                "Notification muted",
                                "The \"Script debugging not available\" notification is now muted. " +
                                    "You can re-enable it under <b>Settings → Tools → Sonarwhale → Scripting</b>.",
                                com.intellij.notification.NotificationType.INFORMATION
                            )
                            .notify(project)
                    })
                    .notify(project)
            }
            onFinished(context)
            return
        }

        ExecutionManager.getInstance(project).restartRunProfile(env)
    }
}

/**
 * RunProfile für ein einzelnes Sonarwhale-Skript.
 *
 * Implementiert [NodeDebugRunConfiguration] — das Interface das
 * [NodeDebugProgramRunner.canRun] per instanceof prüft. Alle drei Methoden
 * haben Default-Implementierungen; wir überschreiben nur [getInterpreter]
 * mit null, da NodeCommandLineUtil Node.js selbst findet.
 */
class SonarwhaleScriptRunProfile(
    private val scriptFile: ScriptFile,
    private val project:    Project,
    private val context:    ScriptContext,
    private val console:    ConsoleOutput,
    private val onFinished: (ScriptContext) -> Unit
) : RunProfile, NodeDebugRunConfiguration {

    override fun getState(executor: com.intellij.execution.Executor, env: ExecutionEnvironment): RunProfileState =
        SonarwhaleDebugRunState(project, env, scriptFile, context, console, onFinished)

    override fun getName(): String = "Debug: ${scriptFile.path.fileName}"
    override fun getIcon(): javax.swing.Icon? = null

    // NodeDebugRunConfiguration hat nur Default-Methoden — keine Overrides nötig.
    // hasConfiguredDebugAddress() → false, getConfiguredDebugPort() → 0, getInterpreter() → null
}
