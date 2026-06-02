package com.sonarwhale.script

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.javascript.debugger.CommandLineDebugConfigurator
import com.intellij.javascript.nodejs.NodeCommandLineUtil
import com.intellij.javascript.nodejs.debug.NodeDebuggableRunProfileState
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import org.jetbrains.concurrency.Promise
import org.jetbrains.concurrency.resolvedPromise

/**
 * RunProfileState für das Debuggen eines Sonarwhale pre/post-Skripts.
 *
 * Implementiert [NodeDebuggableRunProfileState] — damit übernimmt der IDE-eigene
 * NodeDebugProgramRunner den gesamten Attach-Workflow:
 *  - ruft [execute] mit einem CommandLineDebugConfigurator auf
 *  - der Configurator injiziert --inspect-brk=127.0.0.1:<port>
 *  - IDE attached den JS-Debugger auf diesem Port automatisch
 *
 * Exakt das gleiche Muster wie DenoRunState im Deno-Plugin.
 */
class SonarwhaleDebugRunState(
    private val project:    Project,
    private val env:        ExecutionEnvironment,
    private val scriptFile: ScriptFile,
    private val context:    ScriptContext,
    private val console:    ConsoleOutput,
    /** Wird auf dem EDT aufgerufen nachdem der Node.js-Prozess beendet ist. */
    val onFinished: (ScriptContext) -> Unit
) : NodeDebuggableRunProfileState {

    override fun execute(configurator: CommandLineDebugConfigurator?): Promise<ExecutionResult> {
        val runnerPath  = extractRunnerPath()
        val contextJson = writeContextJson()
        val outFile     = java.io.File(contextJson.parent, "sw_context.out.json")

        // IDE-eigenen Node.js-Interpreter verwenden (Settings → Languages & Frameworks → Node.js)
        val interpreter = NodeJsInterpreterManager.getInstance(project).interpreter
            ?: throw com.intellij.execution.ExecutionException(
                "No Node.js interpreter configured. Please set one under Settings → Languages & Frameworks → Node.js."
            )

        val cmd = NodeCommandLineUtil.createCommandLine(false).apply {
            addParameter(runnerPath)
            addParameter(contextJson.absolutePath)
            addParameter(scriptFile.path.toAbsolutePath().toString())
            withWorkDirectory(scriptFile.path.parent.toFile())
        }

        // configureCommandLine setzt den Node.js-Executable-Pfad aus dem IDE-Interpreter
        // und lässt den Configurator --inspect-brk injizieren.
        // Der Consumer bekommt isNodeJs=true und kann weitere Node-spezifische Flags setzen.
        NodeCommandLineUtil.configureCommandLine(cmd, configurator, interpreter) { /* isNodeJs — keine weiteren Flags nötig */ }

        val processHandler = createProcessHandler(cmd, configurator)

        processHandler.addProcessListener(object : com.intellij.execution.process.ProcessListener {
            override fun processTerminated(event: com.intellij.execution.process.ProcessEvent) {
                if (outFile.exists()) applyResultsFromFile(outFile)
                contextJson.parentFile?.deleteRecursively()
                ApplicationManager.getApplication().invokeLater {
                    onFinished(context)
                }
            }
        })

        val ideConsole = ConsoleViewImpl(project, true)
        ideConsole.attachToProcess(processHandler)

        return resolvedPromise(DefaultExecutionResult(ideConsole, processHandler))
    }

    private fun createProcessHandler(
        cmd:          GeneralCommandLine,
        configurator: CommandLineDebugConfigurator?
    ): ProcessHandler = NodeCommandLineUtil.createProcessHandler(cmd, false, configurator)

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun writeContextJson(): java.io.File {
        val tmpDir = java.nio.file.Files.createTempDirectory("sw-debug-").toFile()
        val file   = java.io.File(tmpDir, "sw_context.json")
        file.writeText(buildContextJson())
        return file
    }

    private fun buildContextJson(): String {
        val gson = com.google.gson.Gson()
        val root = com.google.gson.JsonObject()

        val envObj = com.google.gson.JsonObject()
        context.envSnapshot.forEach { (k, v) -> envObj.addProperty(k, v) }
        root.add("env", envObj)

        val reqObj = com.google.gson.JsonObject()
        reqObj.addProperty("url",    context.request.url)
        reqObj.addProperty("method", context.request.method)
        reqObj.addProperty("body",   context.request.body)
        val headersObj = com.google.gson.JsonObject()
        context.request.headers.forEach { (k, v) -> headersObj.addProperty(k, v) }
        reqObj.add("headers", headersObj)
        root.add("request", reqObj)

        context.response?.let { resp ->
            val respObj = com.google.gson.JsonObject()
            respObj.addProperty("status", resp.status)
            respObj.addProperty("body",   resp.body)
            val respHeaders = com.google.gson.JsonObject()
            resp.headers.forEach { (k, v) -> respHeaders.addProperty(k, v) }
            respObj.add("headers", respHeaders)
            root.add("response", respObj)
        }

        return gson.toJson(root)
    }

    private fun applyResultsFromFile(outFile: java.io.File) {
        try {
            val gson = com.google.gson.Gson()
            val root = gson.fromJson(outFile.readText(), com.google.gson.JsonObject::class.java)

            root.getAsJsonObject("env")?.entrySet()?.forEach { (k, v) ->
                context.envSnapshot[k] = v.asString
            }
            root.getAsJsonObject("request")?.let { req ->
                req.get("url")?.asString?.let  { context.request.url  = it }
                req.get("body")?.asString?.let { context.request.body = it }
                req.getAsJsonObject("headers")?.entrySet()?.forEach { (k, v) ->
                    context.request.headers[k] = v.asString
                }
            }
            root.getAsJsonArray("testResults")?.forEach { el ->
                val obj    = el.asJsonObject
                val name   = obj.get("name")?.asString    ?: "unnamed"
                val passed = obj.get("passed")?.asBoolean ?: false
                val error  = obj.get("error")?.takeIf { !it.isJsonNull }?.asString
                context.testResults.add(TestResult(name, passed, error))
            }
        } catch (e: Exception) {
            console.log(LogLevel.ERROR, "Fehler beim Lesen der Debug-Ergebnisse: ${e.message}")
        }
    }

    private fun extractRunnerPath(): String {
        synchronized(runnerLock) {
            cachedRunnerPath?.let { if (java.io.File(it).exists()) return it }
            val tmp = java.nio.file.Files.createTempFile("sw-runner-", ".js").toFile()
            tmp.deleteOnExit()
            val stream = SonarwhaleDebugRunState::class.java.getResourceAsStream("/sw-runner.js")
                ?: throw IllegalStateException("sw-runner.js nicht in Plugin-Resources gefunden")
            tmp.outputStream().use { out -> stream.copyTo(out) }
            cachedRunnerPath = tmp.absolutePath
            return tmp.absolutePath
        }
    }
}

private val runnerLock        = Any()
private var cachedRunnerPath: String? = null
