package com.sonarwhale.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.Alarm
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.ApiEndpoint
import com.sonarwhale.model.CollectionEnvironment
import com.sonarwhale.model.EnvironmentSource
import com.sonarwhale.model.SonarwhaleEnvironment
import com.sonarwhale.openapi.OpenApiFetcher
import com.sonarwhale.openapi.OpenApiParser

/**
 * Haupt-Service: hält die Endpoint-Liste, triggert OpenAPI-Fetches, notifiziert die UI.
 * Ersetzt den alten SonarwhaleService (PSI-Basis).
 *
 * Endpoints are stored per-collection; IDs are prefixed with "{collectionId}:{method} {path}".
 */
@Service(Service.Level.PROJECT)
class RouteIndexService(private val project: Project) : Disposable {

    enum class FetchStatus { OK, CACHED, ERROR, LOADING }

    private val endpointsByCollection = mutableMapOf<String, List<ApiEndpoint>>()
    private var fetchStatus: FetchStatus = FetchStatus.OK

    /** The endpoint ID currently shown in the detail view (set by selectEndpoint / runRequest). */
    @Volatile var currentEndpointId: String? = null
        private set

    private val endpointListeners   = mutableListOf<(List<ApiEndpoint>) -> Unit>()
    private val loadingListeners    = mutableListOf<(Boolean) -> Unit>()
    private val statusListeners     = mutableListOf<(FetchStatus) -> Unit>()
    private val selectionListeners  = mutableListOf<(String) -> Unit>()
    private val runRequestListeners = mutableListOf<(endpointId: String, requestId: String) -> Unit>()

    // Debounced file-save trigger: 500ms after last relevant file change
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    // Periodic refresh for ServerUrl and FilePath sources — interval read from settings
    private val intervalAlarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)
    private val refreshIntervalMs: Long
        get() = SonarwhaleStateService.getInstance(project)
            .getGeneralSettings().autoRefreshIntervalSeconds.toLong() * 1000

    /** Flattened view of all endpoints across all collections (backwards-compatible). */
    val endpoints: List<ApiEndpoint> get() = allEndpoints()

    fun allEndpoints(): List<ApiEndpoint> = endpointsByCollection.values.flatten()

    fun getEndpointsForCollection(collectionId: String): List<ApiEndpoint> =
        endpointsByCollection[collectionId] ?: emptyList()

    /** Clears all endpoint data and notifies UI. Used when project is deactivated. */
    fun clear() {
        endpointsByCollection.clear()
        ApplicationManager.getApplication().invokeLater {
            endpointListeners.toList().forEach { it(emptyList()) }
        }
    }

    fun getCollectionId(endpointId: String): String? =
        endpointsByCollection.entries.firstOrNull { (_, eps) ->
            eps.any { it.id == endpointId }
        }?.key

    init {
        val bus = project.messageBus.connect(this)

        // File-save trigger: refresh on JSON/YAML changes
        bus.subscribe(
            com.intellij.openapi.vfs.VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val projectBase = project.basePath ?: return
                    val relevant = events.any { e ->
                        val ext = e.file?.extension?.lowercase()
                        if (ext !in listOf("json", "yaml", "yml")) return@any false
                        e.file?.path?.startsWith(projectBase) == true
                    }
                    if (!relevant) return
                    refreshAlarm.cancelAllRequests()
                    refreshAlarm.addRequest({ refresh() }, 500)
                }
            }
        )

        scheduleIntervalRefresh()
    }

    /** Returns true when any collection has an active source that warrants automatic refresh. */
    private fun shouldAutoRefresh(): Boolean {
        val collectionService = CollectionService.getInstance(project)
        return collectionService.getAll().any { col ->
            val source = collectionService.getActiveSource(col.id)
            source != null && source !is EnvironmentSource.StaticImport
        }
    }

    /** Schedules a recurring refresh for live sources. Reschedules itself after each run. */
    private fun scheduleIntervalRefresh() {
        val interval = refreshIntervalMs
        if (interval == 0L || !shouldAutoRefresh()) return
        intervalAlarm.addRequest({
            refresh()
            scheduleIntervalRefresh()
        }, interval)
    }

    /** Cancels any pending interval refresh and reschedules with the current settings value. */
    fun restartIntervalRefresh() {
        intervalAlarm.cancelAllRequests()
        scheduleIntervalRefresh()
    }

    // -------------------------------------------------------------------------
    // Refresh — läuft vollständig im Background
    // -------------------------------------------------------------------------

    fun refresh() {
        val collectionService = CollectionService.getInstance(project)
        val collections = collectionService.getAll()

        if (collections.isEmpty()) {
            setEndpoints(emptyList())
            updateStatus(FetchStatus.ERROR)
            return
        }

        notifyLoading(true)
        updateStatus(FetchStatus.LOADING)

        ApplicationManager.getApplication().executeOnPooledThread {
            var overallStatus = FetchStatus.OK
            val newEndpointsByCollection = mutableMapOf<String, List<ApiEndpoint>>()

            for (col in collections) {
                val activeEnv: CollectionEnvironment =
                    collectionService.getActiveEnvironment(col.id) ?: continue
                val source = activeEnv.source

                // Build a SonarwhaleEnvironment wrapper so we can reuse OpenApiFetcher.fetch
                val envWrapper = SonarwhaleEnvironment(
                    id = activeEnv.id,
                    name = activeEnv.name,
                    source = source,
                    isActive = true,
                    sourceAuth = activeEnv.sourceAuth
                )

                val cachedJson = collectionService.readCache(activeEnv.id)
                val result = OpenApiFetcher.fetch(envWrapper, cachedJson)

                val (json, endpointSource, colStatus) = when (result) {
                    is OpenApiFetcher.FetchResult.Success -> Triple(result.json, result.source, FetchStatus.OK)
                    is OpenApiFetcher.FetchResult.Cached  -> Triple(result.json, result.source, FetchStatus.CACHED)
                    is OpenApiFetcher.FetchResult.Error   -> Triple(null, null, FetchStatus.ERROR)
                }

                if (result is OpenApiFetcher.FetchResult.Success) {
                    collectionService.writeCache(activeEnv.id, result.json)
                }

                val parsed: List<ApiEndpoint> = if (json != null && endpointSource != null) {
                    OpenApiParser.parse(json, endpointSource)
                } else {
                    // fall back to what we had before
                    endpointsByCollection[col.id] ?: emptyList()
                }

                // Prefix each endpoint ID with the collection ID
                val prefixed = parsed.map { ep -> ep.copy(id = "${col.id}:${ep.id}") }
                newEndpointsByCollection[col.id] = prefixed

                if (colStatus != FetchStatus.OK && overallStatus == FetchStatus.OK) {
                    overallStatus = colStatus
                }
            }

            ApplicationManager.getApplication().invokeLater {
                endpointsByCollection.clear()
                endpointsByCollection.putAll(newEndpointsByCollection)
                // Notify listeners with the full flattened list
                val snapshot = allEndpoints()
                endpointListeners.toList().forEach { it(snapshot) }
                updateStatus(overallStatus)
                notifyLoading(false)
            }
        }
    }

    /** Setzt Endpoints zurück und führt einen vollständigen Refresh durch. */
    fun reScan() {
        endpointsByCollection.clear()
        endpointListeners.toList().forEach { it(emptyList()) }
        refresh()
    }

    // -------------------------------------------------------------------------
    // Endpoint-Verwaltung
    // -------------------------------------------------------------------------

    fun setEndpoints(endpoints: List<ApiEndpoint>) {
        // Replace all collections' data with a single "unkeyed" bucket for backwards compatibility
        endpointsByCollection.clear()
        if (endpoints.isNotEmpty()) {
            endpointsByCollection["__legacy__"] = endpoints
        }
        val snapshot = allEndpoints()
        ApplicationManager.getApplication().invokeLater {
            endpointListeners.toList().forEach { it(snapshot) }
        }
    }

    // -------------------------------------------------------------------------
    // Selection & Run-Request (für Gutter Icons)
    // -------------------------------------------------------------------------

    /** Updates the currently viewed endpoint ID without triggering any listener. Called by the
     *  UI when the user selects an endpoint directly in the tree (not via gutter icon). */
    fun setCurrentEndpoint(id: String?) {
        currentEndpointId = id
    }

    fun selectEndpoint(id: String) {
        currentEndpointId = id
        ApplicationManager.getApplication().invokeLater {
            selectionListeners.toList().forEach { it(id) }
        }
    }

    fun runRequest(endpointId: String, requestId: String) {
        currentEndpointId = endpointId
        ApplicationManager.getApplication().invokeLater {
            runRequestListeners.toList().forEach { it(endpointId, requestId) }
        }
    }

    // -------------------------------------------------------------------------
    // Listener-Registrierung
    // -------------------------------------------------------------------------

    fun addListener(l: (List<ApiEndpoint>) -> Unit): () -> Unit {
        endpointListeners += l; return { endpointListeners -= l }
    }

    fun addLoadingListener(l: (Boolean) -> Unit): () -> Unit {
        loadingListeners += l; return { loadingListeners -= l }
    }

    fun addStatusListener(l: (FetchStatus) -> Unit): () -> Unit {
        statusListeners += l; return { statusListeners -= l }
    }

    fun addSelectionListener(l: (String) -> Unit): () -> Unit {
        selectionListeners += l; return { selectionListeners -= l }
    }

    fun addRunRequestListener(l: (String, String) -> Unit): () -> Unit {
        runRequestListeners += l; return { runRequestListeners -= l }
    }

    // -------------------------------------------------------------------------
    // Intern
    // -------------------------------------------------------------------------

    private fun notifyLoading(loading: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            loadingListeners.toList().forEach { it(loading) }
        }
    }

    private fun updateStatus(status: FetchStatus) {
        fetchStatus = status
        ApplicationManager.getApplication().invokeLater {
            statusListeners.toList().forEach { it(status) }
        }
    }

    override fun dispose() {
        endpointListeners.clear()
        loadingListeners.clear()
        statusListeners.clear()
        selectionListeners.clear()
        runRequestListeners.clear()
    }

    companion object {
        fun getInstance(project: Project): RouteIndexService = project.service()
    }
}
