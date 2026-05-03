# Sonarwhale TODO

## Code Quality (from review)

### High priority
- Extract duplicated script toggle grid — `RequestPanel.kt:231-264` and `HierarchyConfigPanel.kt:111-145` build the same checkbox grid twice; extract to a shared helper
- Consolidate HTTP method color map — `DetailPanel.kt` and `EndpointTree.kt` both define the same GET/POST/DELETE colors; one should reference the other
- Break up `sendRequest()` — `RequestPanel.kt` doInBackground is 184 lines; extract `applyBodyContent()` and `buildHttpRequest()` helpers

### Medium priority
- Replace stringly-typed script levels — `disabledPreLevels: Set<String>` in HierarchyConfig crosses serialization boundaries raw; silent failure on invalid level names
- Consolidate `RequestPanel` state fields — `currentEndpoint`, `currentRequest`, `currentRequestName`, `previewMode` → sealed `RequestState` class
- Deduplicate context menu builders in `EndpointTree.kt:194-378` — 5 near-identical builders (~184 lines of copy-paste)

### Low priority
- Remove obsolete compat stubs — `SonarwhaleStateService.kt:147-165` has 6 `@deprecated` environment methods with no removal plan
- Move `BodyPanel` content-type list to `ContentTypeUtils` — 4 hardcoded strings in combo box
- Extract shared logic from `showEndpoint()` / `showRequest()` in `DetailPanel`

## Features

### Script debugging (deferred)
- Line numbers in error messages (catch RhinoException, extract lineNumber/columnNumber/getScriptStack)
- Rhino Swing debugger (Debug button in RequestPanel, attach Dim to ContextFactory, step/breakpoints/variable inspection)
- Execution delta after pre-script (show which env vars changed, final URL/headers/body vs initial)

## Phase 3 (Diff & Snapshots)
- SnapshotService — diff after each refresh, delta: added/modified/removed (ID-based)
- REMOVED endpoints in tree (rendering already prepared in EndpointTree)
- Diff tab in Detail panel
- Breaking-change badge on Tool Window icon

## Phase 5 (More Language Scanners)
- PythonScanner — FastAPI / Flask
- JavaScanner — Spring Boot
