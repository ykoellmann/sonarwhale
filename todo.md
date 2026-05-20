# Sonarwhale TODO

> Für Architektur, Gesamtkontext und abgeschlossene Meilensteine siehe `.claude/claude.md`.

## Phase 3 — Diff & Snapshots
- [ ] SnapshotService — diff after each refresh, delta: added/modified/removed (ID-based)
- [ ] REMOVED endpoints in tree (rendering already prepared in EndpointTree)
- [ ] Diff tab in Detail panel
- [ ] Breaking-change badge on Tool Window icon

## Phase 5 — Noch offen
- [ ] Auth für den Swagger-Endpunkt selbst (Header/Token beim Fetch)
- [ ] Import/Export: Postman Collection Import

## Weitere Language Scanner
- [ ] **TypeScript/JavaScript — NestJS** — Dekorator-Syntax (`@Controller`, `@Get`, `@Post`, …), strukturell ähnlich zu JavaScanner; Dateierweiterungen `ts`, `js`
- [ ] **TypeScript/JavaScript — Express** — `router.get('/path', ...)` / `app.post(...)`, kein Decorator-Muster, Regex-basiert; Dateierweiterungen `ts`, `js` (gemeinsam mit NestJS-Scanner oder eigene Klasse)
- [ ] **Go — Gin/Echo** — `r.GET("/path", handler)` / `e.GET("/path", handler)`; Dateierweiterung `go`

## Freemium Model (Foundation)

### Silas ist Hier

### Architecture & Licensing
- [ ] Integrate JetBrains Marketplace licensing API (isLicensed checks)
- [ ] Define productCode, release-date, release-version in plugin.xml
- [ ] Add license verification calls in plugin startup/feature access
- [ ] Create @Premium annotation for gating premium features
- [ ] Local state storage in `.idea/` (no cloud, no accounts)

### Free Tier (1 Environment)
- [ ] Last 10 requests history

### Premium Tier Features
- [ ] **Request History & Collections Management** — unlimited history, groupable/searchable collections
- [ ] **Response Assertions & Test Suite** — test() helpers, assertions tab, test history
- [ ] **Unlimited Environments** — switch between dev/staging/prod with full vars
- [ ] **Endpoint Diff Tracking** — see added/modified/removed endpoints on refresh
- [ ] **Auth Helpers** — pre-built OAuth2, JWT, API Key templates for scripts
- [ ] **Team Features** — shared environments, collection sharing (future: workspace)
- [ ] **Postman Export** — export collections + environments + scripts

## Code Quality

### Medium priority
- [ ] Replace stringly-typed script levels — `disabledPreLevels: Set<String>` in HierarchyConfig crosses serialization boundaries raw; silent failure on invalid level names
- [ ] Consolidate `RequestPanel` state fields — `currentEndpoint`, `currentRequest`, `currentRequestName`, `previewMode` → sealed `RequestState` class

## Features

### Script debugging (deferred)
- [ ] Line numbers in error messages (catch RhinoException, extract lineNumber/columnNumber/getScriptStack)
- [ ] Rhino Swing debugger (Debug button in RequestPanel, attach Dim to ContextFactory, step/breakpoints/variable inspection)
- [ ] Execution delta after pre-script (show which env vars changed, final URL/headers/body vs initial)

## Offene Entscheidungen
- Finaler Name (Kandidaten: Blip, Sonarwhale)
- Monetarisierungsmodell (Freemium / kostenlos / Marketplace-Paid)
- Standalone App als langfristiges Ziel (nach Plugin-MVP)


Dashboard / Analytics