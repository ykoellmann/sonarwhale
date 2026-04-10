# Routex (Arbeitstitel: Blip) — JetBrains Rider Plugin

## Was ist das?

Ein JetBrains Rider Plugin, das API-Endpoints aus OpenAPI-Quellen einliest und einen integrierten HTTP-Client dafür bereitstellt. Ziel: Endpoints direkt aus der IDE heraus testen, ohne Postman oder Browser. Primär für ASP.NET Core, langfristig framework-agnostisch (FastAPI, Spring Boot, Express, ...).

Mascot: Roux (ein Narwal). Name in Diskussion: Blip.

---

## Architektur-Übersicht

```
┌──────────────────────────────────────────────────────────┐
│  DATENQUELLE — pro Environment eine, OpenAPI ist Pflicht │
│                                                          │
│  Option A: Server-URL + Port                             │
│    → HTTP-Fetch gegen bekannte OpenAPI-Pfade             │
│    → Auto-Discovery (probiert Pfade durch, s.u.)        │
│    → Optional: manueller Pfad-Override                  │
│                                                          │
│  Option B: Dateipfad im Filesystem                       │
│    → z.B. ./bin/Debug/net8.0/swagger.json               │
│    → wird bei Build-Event neu gelesen                    │
│    → kein Server nötig                                   │
│                                                          │
│  Option C: Statischer Import (einmalig)                  │
│    → OpenAPI JSON/YAML Datei einmalig hochladen          │
│    → kein Auto-Refresh, manueller Re-Import möglich      │
└──────────────────────────────────────────────────────────┘
         ↓ (aktives Environment liefert OpenAPI-Daten)
┌──────────────────────────────────────────────────────────┐
│  OpenApiFetcher (async, Background)                      │
│  - führt Fetch/Lesen durch                              │
│  - bei Fehler: letzten Cache verwenden                  │
│  - Status-Icon im Tool Window (ok / cached / error)     │
└──────────────────────────────────────────────────────────┘
         ↓
┌──────────────────────────────┐
│  RouteIndexService           │
│  - Endpoint-Liste aufbauen  │
│  - Schemas speichern        │
│  - Auth-Infos halten        │
│  - Cache persistieren       │
└──────────────────────────────┘
         ↓ (zwei unabhängige Pfade)
┌──────────────────┐    ┌───────────────────────────┐
│  PSI-Bridge      │    │  Diff/Snapshot-Engine     │
│  (nur Navigation)│    │                           │
│  - Route-String  │    │  - Snapshot nach Refresh  │
│    → PsiElement  │    │  - Delta: added/modified/ │
│  - Jump to Def.  │    │    removed Endpoints      │
│  - Gutter Icons  │    │  - Breaking-Change-Badge  │
└──────────────────┘    └───────────────────────────┘
         ↓
┌──────────────────────────────────────────────────────┐
│  Tool Window UI                                      │
│                                                      │
│  ┌─────────────────┐  ┌──────────────────────────┐  │
│  │ Endpoint-Tree   │  │ Detail-Panel             │  │
│  │                 │  │ - Tabs: Request/Response │  │
│  │ ▼ GET           │  │ - Auth-Konfiguration     │  │
│  │   /api/users    │  │ - Headers/Body-Editor    │  │
│  │ ▼ POST          │  │ - Diff-Tab               │  │
│  │   /api/users    │  │ - Schema-Vorschau        │  │
│  └─────────────────┘  └──────────────────────────┘  │
└──────────────────────────────────────────────────────┘
```

---

## Environments & Konfiguration

Pro Projekt können mehrere Environments angelegt werden. Start-Default ist `dev`. Eines ist immer aktiv.

```kotlin
data class RoutexEnvironment(
    val name: String,                  // z.B. "dev", "staging", "prod"
    val source: EnvironmentSource,
    val isActive: Boolean
)

sealed class EnvironmentSource {
    // Option A: Server-URL, Port manuell
    data class ServerUrl(
        val host: String,              // z.B. "http://localhost"
        val port: Int,                 // z.B. 5000
        val openApiPath: String?,      // null → Auto-Discovery
        // TODO (später): auth für den Swagger-Endpunkt selbst (Header/Token)
    ) : EnvironmentSource()

    // Option B: Dateipfad im Filesystem
    data class FilePath(
        val path: String               // z.B. "./bin/Debug/net8.0/swagger.json"
    ) : EnvironmentSource()

    // Option C: Statisch importierte Datei (kein Auto-Refresh)
    data class StaticImport(
        val cachedContent: String      // gespeicherter JSON-Inhalt
    ) : EnvironmentSource()
}
```

### OpenAPI Auto-Discovery (Option A)

Bekannte Pfade werden der Reihe nach probiert, erster Treffer gewinnt. Manueller Override hat Vorrang.

```
ASP.NET Core (Swashbuckle):      /swagger/v1/swagger.json
ASP.NET Core (Microsoft.OpenApi): /openapi/v1.json
FastAPI (Python):                 /openapi.json
Spring Boot (Java, springdoc):   /v3/api-docs
Express + swagger-jsdoc:         /api-docs (häufig, aber konfigurierbar)
```

Das Format (OpenAPI JSON/YAML) ist bei allen gleich — nur der Pfad variiert.

---

## Fetch-Trigger & Refresh-Strategie

Alle Fetch-Operationen laufen **asynchron im Background**, niemals im EDT.

| Trigger | Gilt für |
|---|---|
| Jede Minute (Intervall) | Option A + B |
| Build-Event | Option A + B |
| File-Save im Projekt | Option A + B (Schutz gegen veraltete Refs) |
| Manueller Refresh-Button | alle |

**Fallback-Verhalten:** Wenn Quelle nicht erreichbar oder Datei nicht lesbar → letzten erfolgreichen Fetch aus Cache verwenden. Im Tool Window wird ein unauffälliges Status-Icon angezeigt (z.B. kleines Warn-Icon neben dem Environment-Selektor). Kein Modal, kein aufdringlicher Hinweis.

**Cache-Persistenz:** Letzter erfolgreicher Stand wird in `.idea/routex/cache/{environment}.json` gespeichert, damit er auch nach IDE-Neustart verfügbar ist.

---

## PSI-Rolle (Navigation only)

PSI wird **ausschließlich für Navigation** genutzt, nie als Datenquelle:

- Gutter Icons neben Controller-Methoden/Minimal-API-Handlers (basieren auf OpenAPI-Daten)
- Jump to Definition: Route-String aus OpenAPI → PsiElement im Code finden
- Kein Endpoint-Discovery via PSI

---

## Kern-Datenmodell

```kotlin
data class ApiEndpoint(
    val id: String,                    // stabile ID: "METHOD /normalized/path"
    val method: HttpMethod,            // GET, POST, PUT, DELETE, PATCH, ...
    val path: String,                  // "/api/users/{id}"
    val summary: String?,              // aus OpenAPI description
    val tags: List<String>,            // Controller-Name oder OpenAPI-Tags
    val parameters: List<ApiParameter>,
    val requestBody: ApiSchema?,
    val responses: Map<Int, ApiSchema>,
    val auth: AuthInfo?,
    val source: EndpointSource,        // OPENAPI_SERVER, OPENAPI_FILE, OPENAPI_STATIC
    val psiNavigationTarget: String?,  // für Jump-to-Definition
    val status: EndpointStatus         // ACTIVE, ADDED, MODIFIED, REMOVED
)

data class ApiParameter(
    val name: String,
    val location: ParameterLocation,   // PATH, QUERY, HEADER, COOKIE
    val required: Boolean,
    val schema: ApiSchema?
)

data class ApiSchema(
    val type: String,
    val properties: Map<String, ApiSchema>?,
    val example: Any?
)

data class AuthInfo(
    val type: AuthType,                // NONE, BEARER, API_KEY, BASIC, OAUTH2
    val scheme: String?
)
```

---

## Persistenz & Import/Export

```
.idea/
└── routex/
    ├── environments.json  ← alle konfigurierten Environments
    ├── cache/
    │   └── {environment}.json  ← letzter erfolgreicher Fetch pro Environment
    ├── snapshot.json      ← letzter bekannter Stand (für Diff)
    └── collections/
        └── *.json         ← manuelle/importierte Collections (Postman v2.1)
```

---

## Manuelle vs. automatisch erkannte Endpoints

Alle Endpoints erscheinen in **einer gemeinsamen Liste**, visuell unterschieden:

- 🔍 Auto-erkannt (aus OpenAPI)
- 📌 Manuell angelegt oder importiert
- Filterbar via Toggle oben im Tool Window
- `REMOVED`-Endpoints bleiben sichtbar mit rotem Icon + durchgestrichen, bis der User sie bestätigt

---

## Technischer Stack

- **Sprache:** Kotlin
- **Build:** Gradle + `intellij {}` Plugin
- **Target:** Rider (primär), IntelliJ IDEA (optional)
- **Min. Platform-Version:** 2024.1
- **C# Backend (ReSharper SDK):** nur noch für PSI-Navigation in C#-Projekten, kein Endpoint-Discovery mehr

---

## Projektstruktur (Ziel nach Migration)

```
routex/
├── .claude/claude.md                  ← diese Datei
├── build.gradle.kts
├── plugin.xml
├── src/main/kotlin/dev/koellmann/routex/
│   ├── model/
│   │   ├── ApiEndpoint.kt             ← NEU: OpenAPI-Datenmodell (ersetzt altes PSI-Modell)
│   │   ├── ApiSchema.kt
│   │   ├── RoutexEnvironment.kt       ← NEU: Environment + EnvironmentSource
│   │   └── Enums.kt                   ← UPDATE: EndpointSource, EndpointStatus, ParameterLocation
│   ├── openapi/
│   │   ├── OpenApiFetcher.kt          ← NEU: async Fetch/Read, alle drei Optionen
│   │   ├── OpenApiDiscovery.kt        ← NEU: Auto-Discovery bekannter Pfade
│   │   └── OpenApiParser.kt           ← NEU: JSON/YAML → List<ApiEndpoint>
│   ├── service/
│   │   ├── RouteIndexService.kt       ← NEU: Haupt-Service (ersetzt RouteXService)
│   │   ├── EnvironmentService.kt      ← NEU: Environment-Verwaltung + Persistenz
│   │   ├── SnapshotService.kt         ← NEU (Phase 3): Diff + Cache-Persistenz
│   │   └── PsiNavigationBridge.kt     ← NEU: Route-String → PsiElement (Navigation only)
│   └── ui/
│       ├── RouteToolWindowFactory.kt  ← UPDATE: bestehend
│       ├── EndpointTree.kt            ← UPDATE: auf neues Datenmodell anpassen
│       ├── DetailPanel.kt             ← UPDATE: bestehend, kleiner Anpassungsbedarf
│       ├── RequestPanel.kt            ← UPDATE: bestehend, Headers/Body-UI bereits gut
│       ├── ResponsePanel.kt           ← KEEP: kaum Änderungen nötig
│       └── EnvironmentSelector.kt     ← NEU: Dropdown + Status-Icon
└── src/rider/Routex.Rider/            ← C# (nur PSI-Navigation für C#-Projekte)
    └── NavigationHelper.cs            ← REDUZIERT: kein Endpoint-Discovery mehr
```

### Bereits vorhandene Dateien (aktueller Stand):

```
src/rider/main/kotlin/com/routex/
├── model/
│   ├── ApiEndpoint.kt           ← altes PSI-Modell (muss ersetzt werden)
│   └── Enums.kt                 ← zum Teil wiederverwendbar
├── providers/
│   ├── EndpointProvider.kt      ← ENTFERNEN: PSI-Discovery-Interface
│   ├── EndpointProviderRegistry.kt ← ENTFERNEN
│   └── csharp/
│       └── CSharpEndpointProvider.kt ← ENTFERNEN
├── toolwindow/
│   ├── RouteXToolWindowFactory.kt ← KEEP/UPDATE
│   ├── RouteXPanel.kt           ← KEEP/UPDATE
│   ├── EndpointTree.kt          ← UPDATE: auf neues Modell anpassen
│   ├── DetailPanel.kt           ← UPDATE: kleinere Anpassungen
│   ├── RequestPanel.kt          ← KEEP: gut ausgebaut (Params-Table, BodyPanel)
│   └── ResponsePanel.kt         ← KEEP: EditorTextField, "Open in Editor"
├── RouteXService.kt             ← ERSETZEN durch RouteIndexService
├── RouteXStateService.kt        ← KEEP: speichert Saved Requests
├── RouteXStartupActivity.kt     ← UPDATE: startet OpenApiFetcher statt PSI-Scan
├── actions/
│   └── OpenInRouteXAction.kt    ← KEEP: funktioniert über cached endpoints
└── collection/
    └── CollectionFormat.kt      ← KEEP: Postman-Export bleibt

src/dotnet/ReSharperPlugin.Routex/
├── ControllerVisitor.cs         ← ENTFERNEN: kein PSI-Discovery mehr
├── MinimalApiVisitor.cs         ← ENTFERNEN
├── EndpointDetector.cs          ← ENTFERNEN
├── RouteXHost.cs                ← ENTFERNEN: Rider Protocol für Discovery
├── RouteXModel.Generated.cs     ← ENTFERNEN: generiertes Protocol-Modell
├── RoutexDtos.cs                ← ENTFERNEN: PSI-DTOs nicht mehr nötig
└── IRoutexZone.cs               ← bleibt (falls C# PSI-Navigation noch nötig)
```

---

## Threading-Regeln (kritisch für IntelliJ Platform)

- PSI **lesen**: immer in `runReadAction {}` oder `ReadAction.compute {}`
- PSI **schreiben**: immer in `WriteCommandAction.runWriteCommandAction(project) {}`
- UI-Updates: immer im EDT — `ApplicationManager.getApplication().invokeLater {}`
- Background-Tasks: `ProgressManager.getInstance().run(Task.Backgroundable(project, "...") { ... })`
- **Niemals** PSI aus einem Background-Thread ohne ReadAction lesen — crasht die IDE

---

## Bevorzugte APIs (IntelliJ Platform 2024+)

```kotlin
// Services
val myService = project.service<MyService>()       // statt ServiceManager.getService()

// Notifications
NotificationGroupManager.getInstance()
    .getNotificationGroup("Routex")
    .createNotification("...", NotificationType.INFORMATION)
    .notify(project)

// File-Änderungen beobachten
VirtualFileManager.getInstance().addAsyncFileListener(...)

// PSI-Änderungen beobachten
PsiManager.getInstance(project).addPsiTreeChangeListener(listener, disposable)
```

---

## Coding-Prinzipien

- Clean Architecture: Service-Layer komplett von UI getrennt
- `RouteIndexService` ist der einzige Zustand — UI nur lesend
- Alle Parser/Analyzer sind pure functions, keine Seiteneffekte
- Kotlin idiomatisch: Extensions, Data Classes, Sealed Classes für Status/Enums
- **Async-Prinzip:** Alles was blockieren könnte (Netzwerk, Datei-I/O, Parsing) läuft im Background via Coroutines. Niemals BlockingIO im EDT. UI zeigt Ladezustand statt zu blockieren.
- Coroutines für async (kein `Thread.sleep`, kein BlockingIO im EDT)
- TDD: Tests vor Implementierung, besonders für Parser, Fetcher und Diff-Engine

---

## Roadmap

### Bereits implementiert (vor Planänderung, PSI-Basis)

- [x] C# Controller Endpoint-Erkennung via PSI (ControllerVisitor, MinimalApiVisitor)
- [x] Rider Protocol Kommunikation C# ↔ Kotlin (RouteXModel.Generated, RouteXHost)
- [x] Tool Window mit Endpoint-Tree (EndpointTree, RouteXPanel)
- [x] HTTP-Client / Request senden (RequestPanel mit java.net.http)
- [x] Response-Anzeige mit JSON-Highlighting (ResponsePanel mit EditorTextField)
- [x] Response "Open in Editor" (Scratch-File)
- [x] Saved Requests per Endpoint (RouteXStateService, XML-Persistenz)
- [x] Postman v2.1 Collection Export (CollectionFormat.kt)
- [x] "Go to Source" aus dem Endpoint-Tree (OpenInRouteXAction)
- [x] Params-Tab mit JBTable (ParamsTablePanel)
- [x] BodyPanel mit Raw/FormData/Binary und Typ-Auswahl
- [x] Headers-Tab mit JBTable
- [x] Incremental File-Level Caching im C# Backend (EndpointDetector mit file-write-time)

### Phase 1 — Migration auf OpenAPI-Basis (aktuell, höchste Priorität)

**Zu entfernende PSI-Discovery-Infrastruktur:**
- [ ] `ControllerVisitor.cs` entfernen
- [ ] `MinimalApiVisitor.cs` entfernen
- [ ] `EndpointDetector.cs` entfernen
- [ ] `RouteXHost.cs` entfernen (Rider Protocol für Discovery)
- [ ] `RouteXModel.Generated.cs` + `.kt` entfernen (oder stark reduzieren)
- [ ] `RoutexDtos.cs` entfernen
- [ ] `EndpointProvider.kt` + `EndpointProviderRegistry.kt` + `CSharpEndpointProvider.kt` entfernen
- [ ] Rider Protocol Dependency aus `plugin.xml` / `build.gradle.kts` entfernen (falls kein C# PSI mehr)

**Neues OpenAPI-Datenmodell:**
- [ ] `ApiEndpoint.kt` auf neues OpenAPI-Modell umschreiben (path, method, summary, tags, source, status, ...)
- [ ] `RoutexEnvironment.kt` + `EnvironmentSource` (sealed class) anlegen
- [ ] `Enums.kt` updaten: `EndpointSource`, `EndpointStatus`, `ParameterLocation`

**OpenAPI-Infrastruktur:**
- [ ] `OpenApiParser.kt` — OpenAPI JSON/YAML → `List<ApiEndpoint>`
- [ ] `OpenApiDiscovery.kt` — Auto-Discovery bekannter Pfade (ASP.NET, FastAPI, Spring, ...)
- [ ] `OpenApiFetcher.kt` — async Fetch für alle drei Quell-Optionen (Server-URL, Dateipfad, statisch)

**Services:**
- [ ] `RouteIndexService.kt` — Haupt-Service (ersetzt `RouteXService.kt`)
- [ ] `EnvironmentService.kt` — Environment-Verwaltung + Persistenz in `.idea/routex/environments.json`
- [ ] Cache-Persistenz in `.idea/routex/cache/{environment}.json`
- [ ] Fallback auf Cache bei nicht erreichbarer Quelle

**UI:**
- [ ] `EnvironmentSelector.kt` — Dropdown mit aktivem Environment + Status-Icon (ok/cached/error)
- [ ] `EndpointTree.kt` anpassen: auf neues `ApiEndpoint`-Modell (tags statt controllerName, path statt route)
- [ ] `DetailPanel.kt` anpassen: summary, tags, source anzeigen
- [ ] `RouteXStartupActivity.kt` anpassen: `OpenApiFetcher` starten statt PSI-Scan

### Phase 2 — Fetch-Trigger & Refresh

- [ ] Build-Event Trigger (MessageBus / CompileContext)
- [ ] File-Save Trigger (`VirtualFileManager.addAsyncFileListener`)
- [ ] 1-Minuten-Intervall (Alarm/Coroutine-Loop)
- [ ] Manueller Refresh-Button im Tool Window

### Phase 3 — Diff & Snapshots

- [ ] `SnapshotService.kt` — Diff nach jedem Refresh
- [ ] Delta-Erkennung: added / modified / removed (ID-basiert: `"METHOD /path"`)
- [ ] REMOVED-Endpoints im Tree anzeigen (durchgestrichen) bis bestätigt
- [ ] Diff-Tab im Detail-Panel
- [ ] Breaking-Change-Badge am Tool Window Icon

### Phase 4 — PSI-Navigation (optional, nach Phase 1-3)

- [ ] `PsiNavigationBridge.kt` — Route-String aus OpenAPI → PsiElement im C#-Code
- [ ] Gutter Icons neben Controller-Methoden (basieren auf OpenAPI-Daten, nicht PSI-Discovery)
- [ ] Jump-to-Definition: OpenAPI-Endpoint → passende Controller-Methode im Code

### Phase 5 — Import/Export & Erweiterungen

- [ ] Environment-Variablen (Platzhalter in URLs/Headers)
- [ ] Auth für den Swagger-Endpunkt selbst (Header/Token beim Fetch)
- [ ] Python Provider Navigation (FastAPI/Flask — JVM PSI für Jump-to-Definition)
- [ ] Java Provider Navigation (Spring Boot)

---

## Was noch nicht entschieden ist

- Finaler Name (Kandidaten: Blip, Routex)
- Monetarisierungsmodell (Freemium / kostenlos / Marketplace-Paid)
- Standalone App als langfristiges Ziel (nach Plugin-MVP)
- Ob PSI-Navigation (Phase 4) überhaupt ins MVP kommt oder erst nach dem OpenAPI-Kern
