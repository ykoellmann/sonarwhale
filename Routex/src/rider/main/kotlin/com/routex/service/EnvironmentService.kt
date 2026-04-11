package com.routex.service

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.routex.model.EnvironmentSource
import com.routex.model.RoutexEnvironment
import java.io.File
import java.util.UUID

/**
 * Verwaltet Environments pro Projekt.
 * Persistiert in .idea/routex/environments.json.
 * Beim ersten Start wird ein Default-Environment "dev" angelegt.
 */
@Service(Service.Level.PROJECT)
class EnvironmentService(private val project: Project) : Disposable {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val environments = mutableListOf<RoutexEnvironment>()
    private val listeners = mutableListOf<() -> Unit>()

    private val storageFile: File
        get() {
            val dir = File(project.basePath ?: "", ".idea/routex")
            dir.mkdirs()
            return File(dir, "environments.json")
        }

    val cacheDir: File
        get() {
            val dir = File(project.basePath ?: "", ".idea/routex/cache")
            dir.mkdirs()
            return dir
        }

    init {
        load()
    }

    // -------------------------------------------------------------------------
    // Abfragen
    // -------------------------------------------------------------------------

    fun getAll(): List<RoutexEnvironment> = environments.toList()

    fun getActive(): RoutexEnvironment? = environments.firstOrNull { it.isActive }

    // -------------------------------------------------------------------------
    // Bearbeiten
    // -------------------------------------------------------------------------

    fun add(env: RoutexEnvironment): RoutexEnvironment {
        val toAdd = if (environments.isEmpty()) env.copy(isActive = true) else env
        environments += toAdd
        save()
        notifyListeners()
        return toAdd
    }

    fun update(env: RoutexEnvironment) {
        val idx = environments.indexOfFirst { it.id == env.id }
        if (idx >= 0) { environments[idx] = env; save(); notifyListeners() }
    }

    fun remove(id: String) {
        val wasActive = environments.find { it.id == id }?.isActive == true
        environments.removeIf { it.id == id }
        if (wasActive && environments.isNotEmpty()) {
            environments[0] = environments[0].copy(isActive = true)
        }
        save()
        notifyListeners()
    }

    fun setActive(id: String) {
        environments.replaceAll { env -> env.copy(isActive = env.id == id) }
        save()
        notifyListeners()
    }

    // -------------------------------------------------------------------------
    // Cache-Zugriff
    // -------------------------------------------------------------------------

    fun readCache(envId: String): String? = runCatching {
        File(cacheDir, "$envId.json").readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun writeCache(envId: String, json: String) = runCatching {
        File(cacheDir, "$envId.json").writeText(json)
    }

    // -------------------------------------------------------------------------
    // Listener
    // -------------------------------------------------------------------------

    fun addListener(l: () -> Unit): () -> Unit {
        listeners += l
        return { listeners -= l }
    }

    private fun notifyListeners() = listeners.toList().forEach { it() }

    // -------------------------------------------------------------------------
    // Persistenz
    // -------------------------------------------------------------------------

    private fun save() {
        runCatching {
            val arr = com.google.gson.JsonArray()
            for (env in environments) arr.add(envToJson(env))
            storageFile.writeText(gson.toJson(arr))
        }
    }

    private fun load() {
        environments.clear()
        val loaded = runCatching {
            val arr = JsonParser.parseString(storageFile.readText()).asJsonArray
            arr.mapNotNull { jsonToEnv(it.asJsonObject) }
        }.getOrNull()

        if (!loaded.isNullOrEmpty()) {
            environments += loaded
        } else {
            // Default-Environment anlegen
            environments += RoutexEnvironment(
                id = UUID.randomUUID().toString(),
                name = "dev",
                source = EnvironmentSource.ServerUrl(host = "http://localhost", port = 5000),
                isActive = true
            )
            save()
        }
    }

    // -------------------------------------------------------------------------
    // JSON-Serialisierung
    // -------------------------------------------------------------------------

    private fun envToJson(env: RoutexEnvironment): JsonObject {
        val obj = JsonObject()
        obj.addProperty("id", env.id)
        obj.addProperty("name", env.name)
        obj.addProperty("isActive", env.isActive)
        val src = JsonObject()
        when (val s = env.source) {
            is EnvironmentSource.ServerUrl -> {
                src.addProperty("type", "serverUrl")
                src.addProperty("host", s.host)
                src.addProperty("port", s.port)
                s.openApiPath?.let { src.addProperty("openApiPath", it) }
            }
            is EnvironmentSource.FilePath -> {
                src.addProperty("type", "filePath")
                src.addProperty("path", s.path)
            }
            is EnvironmentSource.StaticImport -> {
                src.addProperty("type", "staticImport")
                src.addProperty("cachedContent", s.cachedContent)
            }
        }
        obj.add("source", src)
        return obj
    }

    private fun jsonToEnv(obj: JsonObject): RoutexEnvironment? = runCatching {
        val id = obj.get("id").asString
        val name = obj.get("name").asString
        val isActive = obj.get("isActive")?.asBoolean ?: false
        val src = obj.getAsJsonObject("source")
        val source: EnvironmentSource = when (src.get("type").asString) {
            "serverUrl" -> EnvironmentSource.ServerUrl(
                host = src.get("host").asString,
                port = src.get("port").asInt,
                openApiPath = src.get("openApiPath")?.asString
            )
            "filePath" -> EnvironmentSource.FilePath(path = src.get("path").asString)
            "staticImport" -> EnvironmentSource.StaticImport(cachedContent = src.get("cachedContent").asString)
            else -> return@runCatching null
        }
        RoutexEnvironment(id = id, name = name, source = source, isActive = isActive)
    }.getOrNull()

    override fun dispose() {
        listeners.clear()
    }

    companion object {
        fun getInstance(project: Project): EnvironmentService = project.service()
    }
}
