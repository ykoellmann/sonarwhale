package com.sonarwhale.script

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
        onFinished: (ScriptContext) -> Unit
    ) {
        val executor   = DefaultDebugExecutor.getDebugExecutorInstance()
        val runProfile = SonarwhaleScriptRunProfile(scriptFile, project, context, console, onFinished)

        // ExecutionEnvironmentBuilder.create findet automatisch den NodeDebugProgramRunner,
        // weil SonarwhaleScriptRunProfile NodeDebugRunConfiguration implementiert und
        // der Executor "Debug" ist — canRun() gibt true zurück.
        val env = ExecutionEnvironmentBuilder
            .create(project, executor, runProfile)
            .build()

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
