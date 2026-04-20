# Script Improvements Design

## Goal

Fix pre/post script execution, add a Postman-style Console tab, enable IDE autocomplete for script files, allow script creation at any hierarchy level via tree context menus and a new folder-settings panel.

---

## 1. Bug Fix: Scripts Not Executing

### Root Cause

Two issues in `ScriptEngine.executeChain()`:

1. `Context.enter()` runs **before** the `try` block. If it throws (or if the two property assignments after it throw), `Context.exit()` is never called — Rhino's thread-local context leaks.
2. In IntelliJ's plugin classloader (`PathClassLoader`), Rhino cannot find its own classes when `Context.enter()` uses the default thread classloader. Fix: swap the thread classloader to the plugin classloader around the Rhino call.

Additionally, `SonarwhaleScriptService.executePreScripts()` and `executePostScripts()` have no try/catch — any uncaught exception from the engine propagates to the SwingWorker `done()` method where test results are silently lost.

### Fix

**`ScriptEngine.executeChain()`:**
```kotlin
fun executeChain(scripts: List<ScriptFile>, context: ScriptContext, console: ConsoleOutput) {
    if (scripts.isEmpty()) return
    val prevCl = Thread.currentThread().contextClassLoader
    Thread.currentThread().contextClassLoader = ScriptEngine::class.java.classLoader
    try {
        val cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        try {
            val scope = cx.initStandardObjects()
            val swObj = buildSwObject(cx, scope, context, console)
            ScriptableObject.putProperty(scope, "sw", swObj)
            // expose console.log
            ScriptableObject.putProperty(scope, "console", buildConsoleObject(console))
            for (script in scripts) {
                console.scriptStart(script)
                runCatching {
                    val code = script.path.readText()
                    cx.evaluateString(scope, code, script.path.name, 1, null)
                }.onFailure { e ->
                    console.error(script, e)
                    context.testResults.add(TestResult(
                        name = "Script error in ${script.path.name}",
                        passed = false,
                        error = e.message ?: e.javaClass.simpleName
                    ))
                }
            }
        } finally {
            Context.exit()
        }
    } finally {
        Thread.currentThread().contextClassLoader = prevCl
    }
}
```

**`SonarwhaleScriptService`:** wrap `engine.executeChain()` in try/catch; on exception add an `ErrorEntry` to `ConsoleOutput` and return empty test results.

---

## 2. Console Output Tab

### New Classes

**`ConsoleEntry.kt`** — sealed class, one file:
```kotlin
sealed class ConsoleEntry {
    abstract val timestampMs: Long

    data class LogEntry(
        override val timestampMs: Long,
        val level: LogLevel,   // LOG, WARN, ERROR
        val message: String
    ) : ConsoleEntry()

    data class HttpEntry(
        override val timestampMs: Long,
        val method: String,
        val url: String,
        val status: Int,       // 0 = network error
        val durationMs: Long,
        val requestHeaders: Map<String, String>,
        val requestBody: String?,
        val responseHeaders: Map<String, String>,
        val responseBody: String,
        val error: String?     // non-null on network failure
    ) : ConsoleEntry()

    data class ErrorEntry(
        override val timestampMs: Long,
        val scriptPath: String,
        val message: String
    ) : ConsoleEntry()

    data class ScriptBoundary(
        override val timestampMs: Long,
        val scriptPath: String,
        val phase: ScriptPhase
    ) : ConsoleEntry()
}

enum class LogLevel { LOG, WARN, ERROR }
```

**`ConsoleOutput.kt`** — thread-safe accumulator, created fresh per request execution:
```kotlin
class ConsoleOutput {
    private val _entries = CopyOnWriteArrayList<ConsoleEntry>()
    val entries: List<ConsoleEntry> get() = _entries

    fun log(level: LogLevel, message: String) { _entries += LogEntry(now(), level, message) }
    fun scriptStart(script: ScriptFile) { _entries += ScriptBoundary(now(), script.path.toString(), script.phase) }
    fun error(script: ScriptFile, e: Throwable) { _entries += ErrorEntry(now(), script.path.toString(), e.message ?: e.javaClass.simpleName) }
    fun http(method: String, url: String, status: Int, durationMs: Long,
             reqHeaders: Map<String, String>, reqBody: String?,
             respHeaders: Map<String, String>, respBody: String, error: String?) {
        _entries += HttpEntry(now(), method, url, status, durationMs, reqHeaders, reqBody, respHeaders, respBody, error)
    }
    private fun now() = System.currentTimeMillis()
}
```

**`ConsolePanel.kt`** — new UI component:
- `JList<ConsoleEntry>` with a custom `ConsoleEntryRenderer`
- Per entry type:
  - `ScriptBoundary` → gray italic, small font: `▶ pre.js`
  - `LogEntry(LOG)` → normal text with timestamp prefix
  - `LogEntry(WARN)` → orange text
  - `LogEntry(ERROR)` → red text
  - `HttpEntry` → one-line summary: `→ POST https://... 200 OK  42ms`; click toggles expansion showing request/response headers and body
  - `ErrorEntry` → red background, shows script name + message
- "Clear" button in top-right corner
- Auto-scrolls to bottom on new entries
- `fun showEntries(entries: List<ConsoleEntry>)` — replaces list content (called from EDT)

### Integration

**`ScriptEngine`:**
- `executeChain()` gains a `ConsoleOutput` parameter (breaking change — propagate through callers)
- `sw.http.*` wrappers: record start time, call `makeHttpCall()`, record duration, call `console.http(...)`
- `buildConsoleObject()`: returns a `NativeObject` with `log`, `warn`, `error` functions that call `console.log(level, message)`

**`SonarwhaleScriptService`:**
- `executePreScripts()` and `executePostScripts()` take and return a `ConsoleOutput`
- Callers share one `ConsoleOutput` instance across pre → HTTP → post

**`RequestPanel`:**
- Creates `val consoleOutput = ConsoleOutput()` at start of `sendRequest()`
- Passes it to `executePreScripts()`, reuses for `executePostScripts()`
- On completion: calls `responsePanel.showConsole(consoleOutput.entries)`

**`ResponsePanel`:**
- Gains a third tab "Console" alongside "Body" and "Tests"
- `fun showConsole(entries: List<ConsoleEntry>)` — delegates to `ConsolePanel.showEntries()`
- If console has entries, switch to Console tab automatically (user can switch back)

---

## 3. IDE Autocomplete for Script Files

### Problem

`sw.d.ts` is generated but IntelliJ does not know `.sonarwhale/scripts/` is a JS project, so no autocomplete.

### Fix

Generate a `jsconfig.json` next to `sw.d.ts` in `.sonarwhale/scripts/`:

```json
{
  "compilerOptions": {
    "checkJs": true,
    "strict": false,
    "target": "ES6"
  },
  "include": ["./**/*.js"],
  "exclude": []
}
```

**`SonarwhaleScriptService.ensureSwDts()`** is extended to also call `ensureJsConfig()` — same pattern: write once, never overwrite if exists.

---

## 4. Tree: Global Root Node

### New Node Type

**`EndpointTree.kt`** — add:
```kotlin
object GlobalNode {
    override fun toString() = "Global"
}
```

### Tree Structure Change

`rebuildTree()` currently builds:
```
root (invisible)
  ControllerNode("Users")
    EndpointNode(GET /api/users)
      RequestNode(...)
```

New structure — root becomes visible, `GlobalNode` is the single visible root:
```
root (invisible)
  GlobalNode
    ControllerNode("Users")
      EndpointNode(GET /api/users)
        RequestNode(...)
```

Changes in `EndpointTree`:
- `isRootVisible = false` stays — the invisible root still wraps everything
- `GlobalNode` is added as the single child of the invisible root
- All `ControllerNode`s become children of `GlobalNode`
- `expandAllRows()` still expands everything
- Selection listener: add `is GlobalNode -> onGlobalSelected?.invoke()` callback
- Context menu: `is GlobalNode -> buildGlobalMenu(group)`
- `var onGlobalSelected: (() -> Unit)? = null` callback added

### Cell Renderer

`GlobalNode` renders as: globe icon (or `AllIcons.Nodes.Package`) + bold "Global" + gray "all endpoints" count.

---

## 5. Context Menu: Script Creation at Any Level

Context menu entries are added per node type:

**GlobalNode:**
- "Create Pre-Script (Global)" → `scriptService.getOrCreateScript(phase=PRE, level=GLOBAL)` → open in editor
- "Create Post-Script (Global)" → same for POST

**ControllerNode:**
- "Create Pre-Script (Tag)" → `scriptService.getOrCreateScript(tag=node.name, phase=PRE, level=TAG)`
- "Create Post-Script (Tag)"

**EndpointNode:**
- (existing items)
- separator
- "Create Pre-Script (Endpoint)" → level=ENDPOINT
- "Create Post-Script (Endpoint)"

**RequestNode:**
- (existing items)
- separator
- "Create Pre-Script (Request)" → level=REQUEST
- "Create Post-Script (Request)"

`getOrCreateScript()` requires endpoint+request for REQUEST/ENDPOINT level; for TAG level only tag name is needed; for GLOBAL no endpoint context is needed. The method signature gains overloads or nullable parameters:

```kotlin
fun getOrCreateScript(phase: ScriptPhase, level: ScriptLevel,
                      tag: String? = null,
                      endpoint: ApiEndpoint? = null,
                      request: SavedRequest? = null): Path
```

Script creation runs in `Task.Backgroundable`, then `FileEditorManager.openFile()` in EDT.

---

## 6. Folder-Settings Panel (Global / Tag / Endpoint)

### New Component: `FolderScriptsPanel.kt`

Replaces the current minimal `showController()` content in `DetailPanel`.

Layout:
```
┌──────────────────────────────────────────────────┐
│  [Icon] Global  /  [Icon] Users                  │  ← breadcrumb header
├──────────────────────────────────────────────────┤
│  Scripts                              [section]  │
│                                                  │
│  Pre    pre.js  (path)           [Edit] [Delete] │
│         — or —                                   │
│  Pre    (no script)                     [Create] │
│                                                  │
│  Post   (no script)                     [Create] │
├──────────────────────────────────────────────────┤
│  Auth                            (coming soon)   │  ← gray, disabled
├──────────────────────────────────────────────────┤
│  Variables                       (coming soon)   │  ← gray, disabled
└──────────────────────────────────────────────────┘
```

**Constructor:** `FolderScriptsPanel(project, level, tag?, endpoint?)`

**Script rows:** checks filesystem for `pre.js`/`post.js` at the appropriate path. If file exists: shows truncated path + Edit button (opens in editor) + Delete button (confirms, deletes). If no file: shows "(no script)" + Create button (calls `getOrCreateScript()` in background, opens result).

**Auth and Variables sections:** rendered as disabled labels with a lock icon and "coming soon" tooltip. No functionality yet — visual placeholder only.

### Integration in `DetailPanel`

- Add `var onGlobalSelected: (() -> Unit)?` to `EndpointTree` wired to `DetailPanel.showGlobal()`
- `showController(node)` → now calls `showFolderPanel(level=TAG, tag=node.name)`
- `showGlobal()` → calls `showFolderPanel(level=GLOBAL)`
- Endpoint node selection (`showEndpoint(endpoint)`) keeps its current behavior: shows the request panel for that endpoint. Endpoint-level scripts are managed exclusively via the context menu — no separate folder panel for endpoints.

`cardPanel` gains a fourth card: `"folder"` → `FolderScriptsPanel` (rebuilt on each show call).

---

## 7. Remove ⚡ from Button Labels

`RequestPanel.kt`:
- `JButton("⚡ Pre")` → `JButton("Pre")`
- `JButton("⚡ Post")` → `JButton("Post")`

---

## Files Modified

| File | Change |
|---|---|
| `script/ScriptEngine.kt` | Classloader fix, Context.enter() in try, ConsoleOutput param, console.log/warn/error, HTTP logging |
| `script/ScriptContext.kt` | No change |
| `script/ConsoleEntry.kt` | New file |
| `script/ConsoleOutput.kt` | New file |
| `script/SonarwhaleScriptService.kt` | try/catch around executeChain, ConsoleOutput threading, getOrCreateScript overload, ensureJsConfig() |
| `toolwindow/ConsolePanel.kt` | New file |
| `toolwindow/ResponsePanel.kt` | Add Console tab, showConsole() |
| `toolwindow/RequestPanel.kt` | ConsoleOutput creation, ⚡ removal |
| `toolwindow/FolderScriptsPanel.kt` | New file |
| `toolwindow/EndpointTree.kt` | GlobalNode, context menu additions, onGlobalSelected callback |
| `toolwindow/DetailPanel.kt` | showGlobal(), showFolderPanel(), new card, wire onGlobalSelected |

---

## Not In Scope

- Auth configuration UI (placeholder only)
- Per-tag/endpoint variables
- Python/Java scanners
- Console output persistence across sessions
