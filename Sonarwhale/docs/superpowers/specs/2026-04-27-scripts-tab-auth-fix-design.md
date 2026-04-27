# Scripts Tab, Layer Toggles & Auth Execution Fix

**Date:** 2026-04-27
**Status:** Approved

---

## Overview

Three related problems are addressed together:

1. **Auth not saved correctly** — `AuthConfigPanel.setAuth()` fires `onChange` mid-population due to Swing listener ordering, corrupting the saved auth config.
2. **Pre/Post buttons need a dedicated Scripts tab** — currently crammed into the URL bar; endpoint-level and request-level script buttons should be separate, with toggles to mute inherited scripts from upper layers.
3. **Pre-script env is always empty; auth is not applied after pre-scripts** — `executePreScripts()` seeds from a deprecated no-op stub, and `sendRequest()` uses a stale `varMap` (built before pre-scripts ran) for auth resolution.

---

## Data Model

### `HierarchyConfig` — two new fields

```kotlin
data class HierarchyConfig(
    val variables: List<VariableEntry> = emptyList(),
    val auth: AuthConfig = AuthConfig(),
    val disabledPreLevels: Set<ScriptLevel> = emptySet(),   // NEW
    val disabledPostLevels: Set<ScriptLevel> = emptySet()   // NEW
)
```

`ScriptLevel` values: `GLOBAL, COLLECTION, TAG, ENDPOINT, REQUEST`.

Each level's Scripts tab only exposes toggles for levels *above* it:

| Current level | Toggleable levels |
|---|---|
| Global | — (nothing above) |
| Collection | Global |
| Tag | Global, Collection |
| Endpoint | Global, Collection, Tag |
| Request | Global, Collection, Tag, Endpoint |

No new model types are needed. `disabledPreLevels` and `disabledPostLevels` are serialized as JSON arrays of `ScriptLevel` enum names alongside the existing `HierarchyConfig` fields.

---

## Auth Load Fix

### `AuthConfigPanel.setAuth()` — suppress onChange during load

Add `private var isLoading = false` to `AuthConfigPanel`. In `setAuth()`, set `isLoading = true` before touching any field and `isLoading = false` after all fields are set. The `emitChange()` and `onModeChanged()` methods check `isLoading` and return early if true.

This prevents the Swing ActionListener on `modeCombo` from firing `buildFromFields()` while sibling fields still hold stale values.

---

## Scripts Tab UI

### `RequestPanel` — remove URL-bar buttons, add Scripts tab

Remove `preScriptButton` and `postScriptButton` from `buildUrlBar()`. Add a **"Scripts"** tab to the `CollapsibleTabPane` alongside Params/Headers/Body/Auth.

Scripts tab layout:

```
Endpoint scripts:  [Open Pre]  [Open Post]
Request scripts:   [Open Pre]  [Open Post]

Disable inherited:
                   Pre   Post
─────────────────────────────
Global             [✓]   [✓]
Collection         [✓]   [✓]
Tag                [✓]   [✓]
Endpoint           [✓]   [✓]
```

**Checkbox semantics:**
- Checked (✓) = inherited script at that level runs normally.
- Unchecked = that level's script is muted for this endpoint/request.
- Endpoint-level row disables for all requests of that endpoint.
- Request-level row overrides for just that request.

**Persistence:**
- Endpoint toggles → `SonarwhaleStateService.getEndpointConfig(endpointId).config.disabledPreLevels / disabledPostLevels`
- Request toggles → `currentRequest.config.disabledPreLevels / disabledPostLevels`
- Auto-saved on any toggle change (same pattern as auth `onChange`).

**Script buttons at request level:**
- "Endpoint Pre/Post" → `openOrCreateScript(phase, ScriptLevel.ENDPOINT)`
- "Request Pre/Post" → `openOrCreateScript(phase, ScriptLevel.REQUEST)`

### `HierarchyConfigPanel` — Scripts tab enhanced

The existing "Open Pre-Script" / "Open Post-Script" buttons are kept. Below them, add the disable grid showing only the levels above the current node (determined by the `ScriptContext.level` passed to the panel).

At Global level, the disable section is empty (nothing to mute).

---

## Script Execution Fixes

### Fix A — Seed env snapshot from `VariableResolver`

Change `SonarwhaleScriptService.executePreScripts()` signature:

```kotlin
fun executePreScripts(
    endpoint: ApiEndpoint,
    request: SavedRequest,
    url: String,
    headers: Map<String, String>,
    body: String,
    varMap: Map<String, String>,   // NEW — caller passes resolved var map
    collectionId: String,          // NEW — for env persistence
    console: ConsoleOutput = ConsoleOutput()
): ScriptContext
```

Inside, replace:
```kotlin
// BEFORE (broken):
val env = stateService.getActiveEnvironment()?.variables?.toMutableMap() ?: mutableMapOf()

// AFTER:
val env = varMap.toMutableMap()
```

This seeds `ctx.envSnapshot` with all resolved variables (baseUrl, collection vars, global vars, etc.) so `sw.env.get("baseUrl")` and friends work correctly.

### Fix B — Rebuild varMap after pre-scripts, before auth

In `RequestPanel.sendRequest()`, after `executePreScripts()` returns:

```kotlin
// Merge script env changes on top of the original varMap
val postScriptVarMap = varMap.toMutableMap().also { it.putAll(ctx.envSnapshot) }
```

Use `postScriptVarMap` (not `varMap`) for:
- `authResolver.applyToRequest()` — so `{{auth_token}}` set by the pre-script resolves correctly
- Header value resolution
- Body value resolution

### Fix C — Persist env changes to `CollectionService`

`SonarwhaleScriptService.flushEnvChanges()` currently calls `stateService.upsertEnvironment()` which is a no-op stub. Fix:

```kotlin
private fun flushEnvChanges(
    snapshot: MutableMap<String, String>,
    originalVarMap: Map<String, String>,
    collectionId: String
) {
    // Only persist keys that the script actually changed
    val changed = snapshot.filter { (k, v) -> originalVarMap[k] != v }
    if (changed.isEmpty()) return

    ApplicationManager.getApplication().invokeLater {
        val collectionService = CollectionService.getInstance(project)
        val collection = collectionService.getById(collectionId) ?: return@invokeLater
        // Merge changed keys into the collection's config variables
        val existing = collection.config.variables.toMutableList()
        changed.forEach { (k, v) ->
            val idx = existing.indexOfFirst { it.key == k }
            if (idx >= 0) existing[idx] = existing[idx].copy(value = v)
            else existing.add(VariableEntry(key = k, value = v, enabled = true))
        }
        collectionService.updateConfig(collectionId, collection.config.copy(variables = existing))
    }
}
```

### Fix D — Chain resolver respects disabled levels

`ScriptChainResolver.resolvePreChain()` and `resolvePostChain()` each get a new parameter:

```kotlin
fun resolvePreChain(
    tag: String,
    method: String,
    path: String,
    requestName: String,
    disabledLevels: Set<ScriptLevel> = emptySet()   // NEW
): List<ScriptFile>
```

Inside `buildChain()`, before appending each `ScriptFile`, check if its `level` is in `disabledLevels`. If so, skip it.

The effective disabled set is computed in `SonarwhaleScriptService` as the **union** of endpoint config and request config disabled sets:

```kotlin
val effectiveDisabledPre  = endpointConfig.config.disabledPreLevels  + request.config.disabledPreLevels
val effectiveDisabledPost = endpointConfig.config.disabledPostLevels + request.config.disabledPostLevels
```

`executePostScripts()` also gains `collectionId: String` and uses `effectiveDisabledPost` when calling `resolvePostChain()`. `flushEnvChanges()` is called from `executePostScripts()` with the final envSnapshot and collectionId.

---

## Execution Order in `sendRequest()`

```
1. Build varMap (VariableResolver.buildMap)
2. Run pre-scripts (executePreScripts with varMap as seed, effectiveDisabledPre)
3. Build postScriptVarMap = varMap + ctx.envSnapshot
4. Apply auth headers (authResolver.applyToRequest with postScriptVarMap)
5. Resolve header/body values (with postScriptVarMap)
6. Send HTTP request
7. Run post-scripts (executePostScripts with effectiveDisabledPost)
8. Flush env changes (changed keys → CollectionService active environment)
```

---

## Files Changed

| File | Change |
|---|---|
| `model/HierarchyConfig.kt` | Add `disabledPreLevels`, `disabledPostLevels` |
| `toolwindow/AuthConfigPanel.kt` | Add `isLoading` flag to suppress onChange during `setAuth()` |
| `toolwindow/RequestPanel.kt` | Remove URL-bar Pre/Post buttons; add Scripts tab with 4 buttons + toggle grid |
| `toolwindow/HierarchyConfigPanel.kt` | Enhance Scripts tab with level-toggle grid |
| `script/ScriptChainResolver.kt` | Add `disabledLevels` param to `buildChain()`, `resolvePreChain()`, `resolvePostChain()` |
| `script/SonarwhaleScriptService.kt` | Add `varMap` + `collectionId` params to `executePreScripts()`; fix `flushEnvChanges()` |
| `service/CollectionService.kt` | Add `updateConfig(collectionId, config)` if not already present |

---

## Testing

- Unit test: `AuthConfigPanel` — verify `onChange` is not fired during `setAuth()`
- Unit test: `ScriptChainResolver` — verify disabled levels are excluded from chain
- Unit test: `VariableResolverPure` — already covered; add case for envSnapshot override
- Manual: pre-script sets `auth_token` → Bearer header uses resolved token
- Manual: endpoint-level disables global pre → global pre.js does not run
- Manual: request-level disables collection pre → collection pre.js does not run for that request only
