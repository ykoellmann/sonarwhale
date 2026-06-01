package com.sonarwhale.service

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.sonarwhale.license.LicenseService
import com.sonarwhale.license.PremiumFeature
import com.sonarwhale.model.RequestRunEntry
import java.io.File

@Service(Service.Level.PROJECT)
class RunHistoryService(private val project: Project) : Disposable {

    private val gson = GsonBuilder().create()
    private val entries = mutableListOf<RequestRunEntry>()
    private val listeners = mutableListOf<() -> Unit>()

    private val storageFile: File
        get() = File(project.basePath ?: "", ".idea/sonarwhale/runs.json")

    init { load() }

    fun add(entry: RequestRunEntry) {
        entries.add(0, entry)
        val limit = if (LicenseService.getInstance().isUnlocked(PremiumFeature.UNLIMITED_HISTORY))
            PREMIUM_LIMIT else LicenseService.FREE_HISTORY_LIMIT
        if (entries.size > limit) entries.subList(limit, entries.size).clear()
        save()
        notifyListeners()
    }

    fun getForEndpoint(collectionId: String, endpointId: String): List<RequestRunEntry> =
        entries.filter { it.collectionId == collectionId && it.endpointId == endpointId }

    fun addListener(l: () -> Unit): () -> Unit { listeners += l; return { listeners -= l } }

    private fun notifyListeners() = listeners.toList().forEach { it() }

    private fun save() = runCatching {
        storageFile.parentFile.mkdirs()
        storageFile.writeText(gson.toJson(entries))
    }

    private fun load() = runCatching {
        val type = object : TypeToken<List<RequestRunEntry>>() {}.type
        val loaded = gson.fromJson<List<RequestRunEntry>>(storageFile.readText(), type)
            ?: return@runCatching
        entries.addAll(loaded)
    }

    override fun dispose() { listeners.clear() }

    companion object {
        private const val PREMIUM_LIMIT = 500
        fun getInstance(project: Project): RunHistoryService = project.service()
    }
}
