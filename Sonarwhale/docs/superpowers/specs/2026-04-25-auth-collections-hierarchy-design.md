# Auth, Collections & Hierarchy Design

## Goal

Restructure Sonarwhale from a single-source, flat-variable model into a multi-collection hierarchy where variables, auth, and scripts are configurable at every level — Global, Collection, Controller/Tag, Endpoint, and SavedRequest — with full inheritance and per-level override capability.

## Architecture

The new model introduces **Collections** as the top-level API grouping concept (analogous to Postman collections). Each Collection represents one API and owns multiple named environments (dev/staging/prod), each holding only an OpenAPI source. Variables, auth, and scripts live in the hierarchy itself — not in environments. A single project can have multiple Collections active simultaneously with different environments.

The hierarchy is:

```
Global
  Collection (one per API, e.g. "UserService", "OrderService")
    Environment (dev / staging / prod — source only)
    Controller/Tag (auto-grouped from OpenAPI tags)
      Endpoint (auto-populated from active environment's OpenAPI)
        SavedRequest
```

Variable resolution, auth resolution, and script execution all walk this chain. Environment contributes one reserved variable (`{{baseUrl}}`) derived from the active environment's source URL — it is never stored manually.

---

## Data Model

### New: `VariableEntry`

```kotlin
// model/VariableEntry.kt
data class VariableEntry(
    val key: String,
    val value: String,
    val enabled: Boolean = true
)
```

### New: `AuthConfig`

Replaces the current header-based auth template. Stored at every level of the hierarchy.

```kotlin
// model/AuthConfig.kt

enum class AuthMode {
    INHERIT,                    // resolve from parent level
    NONE,                       // send no auth (explicitly empty)
    BEARER,                     // Authorization: Bearer <token>
    BASIC,                      // Authorization: Basic base64(user:pass)
    API_KEY,                    // header or query param, configurable
    OAUTH2_CLIENT_CREDENTIALS   // POST token_url with client_id/secret, cache token
    // Future: OAUTH2_PKCE
}

data class AuthConfig(
    val mode: AuthMode = AuthMode.INHERIT,
    // BEARER
    val bearerToken: String = "",               // supports {{vars}}
    // BASIC
    val basicUsername: String = "",
    val basicPassword: String = "",
    // API_KEY
    val apiKeyName: String = "",                // header/param name
    val apiKeyValue: String = "",
    val apiKeyLocation: ApiKeyLocation = ApiKeyLocation.HEADER,
    // OAUTH2_CLIENT_CREDENTIALS
    val oauthTokenUrl: String = "",
    val oauthClientId: String = "",
    val oauthClientSecret: String = "",
    val oauthScope: String = "",                // space-separated, optional
    val oauthGrantType: String = "client_credentials"
)

enum class ApiKeyLocation { HEADER, QUERY }
```

### New: `ScriptConfig`

Each level can have a pre- and post-script, each with a mode that controls whether parent scripts still run.

```kotlin
// model/ScriptConfig.kt

enum class ScriptMode {
    EXTEND,     // run in addition to parent scripts (default)
    OVERRIDE    // skip all parent scripts for this level and below
}

data class ScriptConfig(
    val scriptPath: String?,        // relative path from .idea/sonarwhale/scripts/
    val mode: ScriptMode = ScriptMode.EXTEND
)
```

### New: `HierarchyConfig`

A reusable config block carried at every level of the tree (Global, Collection, Tag, Endpoint, SavedRequest).

```kotlin
// model/HierarchyConfig.kt
data class HierarchyConfig(
    val variables: List<VariableEntry> = emptyList(),
    val auth: AuthConfig = AuthConfig(),          // default: INHERIT
    val preScript: ScriptConfig? = null,
    val postScript: ScriptConfig? = null
)
```

### New: `ApiCollection`

Replaces `SonarwhaleEnvironment`. A Collection groups one API's environments and owns the collection-level `HierarchyConfig`.

```kotlin
// model/ApiCollection.kt
data class ApiCollection(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val environments: List<CollectionEnvironment> = emptyList(),
    val activeEnvironmentId: String? = null,
    val config: HierarchyConfig = HierarchyConfig()
)

data class CollectionEnvironment(
    val id: String = UUID.randomUUID().toString(),
    val name: String,                   // "dev", "staging", "prod"
    val source: EnvironmentSource       // existing sealed class — unchanged
)
```

`SonarwhaleEnvironment` is **removed**. `EnvironmentSource` sealed class is **kept unchanged**.

### New: `GlobalConfig`

Project-wide top of the hierarchy. Stored in `sonarwhale.xml` via `SonarwhaleStateService`.

```kotlin
// model/GlobalConfig.kt
data class GlobalConfig(
    val config: HierarchyConfig = HierarchyConfig()
)
```

### New: `TagConfig`

Per controller/tag config. Keyed by tag name string. Stored in `SonarwhaleStateService`.

```kotlin
// model/TagConfig.kt
data class TagConfig(
    val tag: String,
    val config: HierarchyConfig = HierarchyConfig()
)
```

### New: `EndpointConfig`

Per-endpoint config. Keyed by endpoint ID. Stored in `SonarwhaleStateService`. Auth is pre-seeded from `ApiEndpoint.auth` (OpenAPI hint) the first time an endpoint is seen.

```kotlin
// model/EndpointConfig.kt
data class EndpointConfig(
    val endpointId: String,
    val config: HierarchyConfig = HierarchyConfig()
)
```

### Modified: `SavedRequest`

Add `HierarchyConfig`. Remove reliance on `headers` field for auth (auth moves to `config.auth`). Headers field remains for explicit custom headers that are NOT auth.

```kotlin
data class SavedRequest(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Default",
    val isDefault: Boolean = false,
    val headers: String = "",               // JSON array of NameValueRow — non-auth headers only
    val body: String = "",
    val bodyMode: String = "none",
    val bodyContentType: String = "application/json",
    val paramValues: Map<String, String> = emptyMap(),
    val paramEnabled: Map<String, Boolean> = emptyMap(),
    // NEW:
    val config: HierarchyConfig = HierarchyConfig()
)
```

### Kept unchanged

- `ApiEndpoint` — `auth: AuthInfo?` stays as the OpenAPI hint (read-only, server's requirement)
- `AuthInfo`, `AuthType` — kept as the OpenAPI hint types (distinct from user-configured `AuthConfig`)
- `EnvironmentSource` sealed class — unchanged
- `Environment` (variable map) — **removed** from `SonarwhaleStateService`; replaced by `HierarchyConfig.variables` at each level

---

## Resolution Algorithms

### Variable Resolution

Called at request send time. Builds a flat map of `key → value`, narrowest scope wins.

Resolution order (lowest to highest priority):
1. Built-in: `{{baseUrl}}` derived from active Collection environment's source URL (lowest priority default — can be overridden at any level)
2. Global `HierarchyConfig.variables`
3. Collection `HierarchyConfig.variables`
4. Tag `HierarchyConfig.variables` (matching the endpoint's first tag)
5. Endpoint `HierarchyConfig.variables`
6. SavedRequest `HierarchyConfig.variables`

`{{baseUrl}}` is automatically seeded from the active environment so switching environments changes the base URL transparently. Any level can override it with an explicit variable entry (e.g. to test one endpoint against a different server).

Result map is applied to URL, all header values, body, and all auth parameter strings using the existing `{{varName}}` regex pattern.

### Auth Resolution

Called at request send time. Walks up the hierarchy until a non-INHERIT mode is found.

```
resolve(request.config.auth)
  if INHERIT → resolve(endpoint.config.auth)
    if INHERIT → resolve(tag.config.auth)
      if INHERIT → resolve(collection.config.auth)
        if INHERIT → resolve(global.config.auth)
          if INHERIT → AuthMode.NONE (no auth sent)
```

After resolving the effective `AuthConfig`, variable substitution is applied to all string fields (token, username, password, etc.) using the resolved variable map.

**Injection into HTTP request:**

| AuthMode | HTTP injection |
|---|---|
| NONE | nothing added |
| BEARER | `Authorization: Bearer <resolved token>` |
| BASIC | `Authorization: Basic base64(<user>:<pass>)` |
| API_KEY / HEADER | `<apiKeyName>: <apiKeyValue>` header |
| API_KEY / QUERY | `?<apiKeyName>=<apiKeyValue>` appended to URL |
| OAUTH2_CLIENT_CREDENTIALS | POST to token_url, cache token in memory per session, inject `Authorization: Bearer <token>`. Token refreshed when expired or on explicit user action. |

Auth-injected headers/params are NOT visible in the Headers table — they are applied after headers are read. This prevents duplicate/conflicting auth entries.

### Script Execution Order

Collect scripts from all levels before sending. Apply OVERRIDE logic:

```
collected = []
for level in [global, collection, tag, endpoint, request]:
    script = level.preScript
    if script != null:
        if script.mode == OVERRIDE:
            collected = [script]   // discard everything above
        else:
            collected += script

execute collected in order
→ send HTTP request
→ same for postScript
```

OVERRIDE at a lower level discards all parent scripts from that phase. OVERRIDE at a higher level discards nothing above it (there is nothing), but prevents children from accumulating further unless they also specify OVERRIDE.

---

## Service Changes

### `EnvironmentService` → `CollectionService`

Rename and rewrite. Manages `List<ApiCollection>` instead of `List<SonarwhaleEnvironment>`.

Key responsibilities:
- CRUD for collections and their child environments
- Track active environment per collection
- Persist to `.idea/sonarwhale/collections.json` (new path)
- Provide `getActiveSource(collectionId)` → `EnvironmentSource?`
- Provide `getBaseUrl(collectionId)` → `String` (derived from active source)
- Cache still stored in `.idea/sonarwhale/cache/{envId}.json` — unchanged

### `RouteIndexService`

Currently fetches one active source. Change to: fetch all collections in parallel, each using its active environment's source. Maintain a map of `collectionId → List<ApiEndpoint>`. Expose combined endpoint list for tree building, with endpoints tagged by their collection ID.

### `SonarwhaleStateService`

Add storage for the new config objects (all serialized as JSON strings):

```
State additions:
  globalConfig: String          // JSON of GlobalConfig
  tagConfigs: LinkedHashMap<String, String>       // tag → JSON of TagConfig
  endpointConfigs: LinkedHashMap<String, String>  // endpointId → JSON of EndpointConfig
```

Remove:
- `environments: String` — moves to `CollectionService` / `collections.json`
- `activeEnvironmentId: String` — moves to `CollectionService`

The `resolveVariables(text)` method is replaced by `VariableResolver` (new service). Keep `SonarwhaleStateService` focused on request/config storage.

### New: `VariableResolver`

```kotlin
// service/VariableResolver.kt
class VariableResolver(project: Project) {
    fun resolve(
        text: String,
        collectionId: String,
        endpointId: String,
        requestId: String?
    ): String

    fun buildMap(
        collectionId: String,
        endpointId: String,
        requestId: String?
    ): Map<String, String>
}
```

Builds the merged variable map from all levels and applies it to the given string.

### New: `AuthResolver`

```kotlin
// service/AuthResolver.kt
class AuthResolver(project: Project) {
    fun resolve(
        collectionId: String,
        endpointId: String,
        requestId: String?
    ): AuthConfig    // always returns a concrete mode (never INHERIT)

    fun applyToRequest(
        builder: HttpRequest.Builder,
        auth: AuthConfig,
        vars: Map<String, String>
    )
}
```

OAuth2 token cache lives here (in-memory, per session, keyed by token URL + client ID).

---

## UI Changes

### Tree (`EndpointTree.kt`)

**New node type:**
```kotlin
class CollectionNode(val collection: ApiCollection, val endpoints: List<ApiEndpoint>)
```

**New tree structure:**
```
GlobalNode                          ← existing, unchanged
  CollectionNode "UserService"      ← new
    ControllerNode "UsersController"
      EndpointNode GET /api/users
        RequestNode "Happy Path"
        RequestNode "Missing Auth"
  CollectionNode "OrderService"     ← new
    ControllerNode "OrdersController"
      ...
```

**Changes to `rebuildTree()`:**
- Group endpoints by collection ID, then by tag within each collection
- Create a `CollectionNode` per collection containing its `ControllerNode` children
- `GlobalNode` stays as the single top-level visible node; collections appear under it

**New callback:**
```kotlin
var onCollectionSelected: ((ApiCollection) -> Unit)? = null
```

**Cell renderer additions:**
- `CollectionNode` — bold name, small env indicator in gray (e.g. "dev"), folder icon
- Existing node renderers unchanged

**Context menu additions for `CollectionNode`:**
- Add Environment, Edit Collection (opens collection detail), Refresh

### Detail Panel (`DetailPanel.kt`)

Add new card views using the existing `CardLayout` pattern:

**New card: `"global"`**
Shows `GlobalDetailPanel` — tabs: Variables, Auth, Scripts

**New card: `"collection"`**
Shows `CollectionDetailPanel` — tabs: Environments (source CRUD), Variables, Auth, Scripts

**New card: `"controller"` (replaces `"folder"`)**
Shows `ControllerDetailPanel` — tabs: Variables, Auth, Scripts (Scripts tab shows Open Pre-Script / Open Post-Script buttons + mode toggle)

**Existing card: `"content"` (endpoint preview + request)**
Endpoint preview mode gets new tabs alongside existing Params/Headers/Body (read-only): Variables, Auth, Scripts. These are editable even in preview mode.

Full request view (`RequestPanel`) gets:
- New Auth tab (alongside Params, Headers, Body) showing `AuthConfigPanel`
- Variables tab showing editable `VariableEntry` list
- Scripts tab showing Open Pre-Script / Open Post-Script buttons + mode toggle

`showGlobal()` switches to `"global"` card.
`showController()` switches to `"controller"` card.
`showCollection()` (new) switches to `"collection"` card.

### New: `HierarchyConfigPanel`

Reusable tabbed panel used at every level. Accepts a `HierarchyConfig` value and a save callback.

```kotlin
class HierarchyConfigPanel(
    project: Project,
    level: HierarchyLevel,              // GLOBAL, COLLECTION, TAG, ENDPOINT, REQUEST
    config: HierarchyConfig,
    onSave: (HierarchyConfig) -> Unit
) : JPanel()
```

Tabs shown depend on `level`:
- All levels: Variables, Auth, Scripts
- REQUEST level additionally: Params, Headers, Body (existing panels embedded here)

Scripts tab contains:
- "Open Pre-Script" button + ScriptMode toggle (Extend / Override)
- "Open Post-Script" button + ScriptMode toggle (Extend / Override)
- Small label showing resolved execution order (informational)

### New: `AuthConfigPanel`

Shows auth configuration for one level. Dropdown: Inherit from parent / No Auth / Bearer / Basic / API Key / OAuth 2 Client Credentials.

Each type shows its relevant fields. "Inherit from parent" shows a read-only badge indicating what is being inherited (resolved type + which level it comes from).

### Environment Selector

Currently a single `JComboBox` in the toolbar for the whole project. This changes to **per-collection** env selectors. Each `CollectionNode` in the tree shows a small env badge (e.g. "dev") in the cell renderer. Clicking the collection detail panel's Environments tab is how you switch environments for that collection.

The toolbar env combo is removed. The settings gear button stays, opening the Settings dialog.

**Fast environment switching:** Right-clicking a `CollectionNode` in the tree shows a "Switch Environment" submenu listing all environments for that collection. The active one is marked with a checkmark. This is the primary quick-switch mechanism. The collection's cell renderer shows the active environment name as a small gray label (e.g. `UserService  dev`) so the current state is always visible without opening the detail panel.

### Settings (`SonarwhaleEnvironmentsConfigurable.kt`)

Simplified. Shows a list of Collections. For each collection: name, list of environments (name + source config). No variables, no auth — those moved to the tree detail views.

Variable management is removed from settings entirely — it lives in the Global node detail view in the tool window.

---

## Migration

When opening a project with the old data format:

1. **`SonarwhaleEnvironment` list** → each entry becomes one `ApiCollection` with one `CollectionEnvironment` (same name, same source). The previously-active environment becomes `activeEnvironmentId` of that collection.

2. **`Environment` variable map** (from `SonarwhaleStateService.environments`) → flattened into `GlobalConfig.config.variables`. Keys and values preserved as-is.

3. **`SavedRequest` records** → each gets `config = HierarchyConfig()` (default: INHERIT everything, no variables, no scripts). The existing `headers` field is left intact. Any `Authorization` header in the headers list is left as a plain header (it will override auth-injected auth — user will need to clean it up manually, but nothing breaks).

4. **Script files** (already in `.idea/sonarwhale/scripts/`) — paths preserved, loaded into `ScriptConfig` with `mode = ScriptMode.EXTEND`.

Migration runs once on first load. A one-time info notification is shown: "Sonarwhale updated — your environments and variables have been migrated to the new collections model."

---

## File Summary

### New files

| File | Purpose |
|---|---|
| `model/VariableEntry.kt` | Single variable key/value/enabled |
| `model/AuthConfig.kt` | AuthConfig, AuthMode, ApiKeyLocation |
| `model/ScriptConfig.kt` | ScriptConfig, ScriptMode |
| `model/HierarchyConfig.kt` | Shared config block for all tree levels |
| `model/ApiCollection.kt` | ApiCollection, CollectionEnvironment |
| `model/GlobalConfig.kt` | GlobalConfig wrapper |
| `model/TagConfig.kt` | TagConfig (keyed by tag name) |
| `model/EndpointConfig.kt` | EndpointConfig (keyed by endpoint ID) |
| `service/CollectionService.kt` | Replaces EnvironmentService — manages ApiCollection list |
| `service/VariableResolver.kt` | Builds merged variable map, applies substitution |
| `service/AuthResolver.kt` | Resolves effective auth, applies to HttpRequest, OAuth token cache |
| `toolwindow/HierarchyConfigPanel.kt` | Reusable Variables/Auth/Scripts tab panel |
| `toolwindow/AuthConfigPanel.kt` | Auth type selector + per-type fields |
| `toolwindow/GlobalDetailPanel.kt` | Detail view for GlobalNode |
| `toolwindow/CollectionDetailPanel.kt` | Detail view for CollectionNode (environments + hierarchy config) |
| `toolwindow/ControllerDetailPanel.kt` | Detail view for ControllerNode (replaces FolderScriptsPanel) |

### Modified files

| File | What changes |
|---|---|
| `model/SavedRequest.kt` | Add `config: HierarchyConfig` field |
| `model/Enums.kt` | `AuthType` kept as OpenAPI hint; `AuthMode`, `ApiKeyLocation`, `ScriptMode` added |
| `SonarwhaleStateService.kt` | Add globalConfig, tagConfigs, endpointConfigs storage; remove environment fields; remove `resolveVariables()` |
| `service/RouteIndexService.kt` references updated | All `EnvironmentService` calls replaced with `CollectionService` |
| `service/RouteIndexService.kt` | Multi-collection fetching; endpoints tagged by collectionId |
| `toolwindow/EndpointTree.kt` | Add `CollectionNode`; update `rebuildTree()`, cell renderer, selection dispatch, context menus |
| `toolwindow/DetailPanel.kt` | Add `"global"`, `"collection"`, `"controller"` cards; wire `showCollection()`, update `showGlobal()` and `showController()` |
| `toolwindow/SonarwhalePanel.kt` | Remove single env combo from toolbar; wire `onCollectionSelected`; remove `buildEnvPanel()` |
| `toolwindow/RequestPanel.kt` | Remove `buildHeadersTemplate()`; add Auth tab via `HierarchyConfigPanel`; use `AuthResolver` + `VariableResolver` in `sendRequest()` |
| `toolwindow/FolderScriptsPanel.kt` | Replaced by `ControllerDetailPanel` (can be deleted) |
| `settings/SonarwhaleEnvironmentsConfigurable.kt` | Simplified to collection + source-only CRUD |

### Removed files

| File | Reason |
|---|---|
| `toolwindow/FolderScriptsPanel.kt` | Replaced by `ControllerDetailPanel` |
| `service/EnvironmentService.kt` | Replaced by `CollectionService` |

---

## Out of Scope (Future)

- OAuth 2.0 Authorization Code / PKCE (requires browser redirect + local callback server)
- Python scanner (`PythonScanner`)
- Java/Spring Boot scanner (`JavaScanner`)
- Diff/Snapshot engine (Phase 3 from roadmap)
- Postman Collection import
