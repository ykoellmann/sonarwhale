# Settings & Environment Refactor Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move `baseUrl` into environment variables, replace the custom settings popup with proper IDE Settings pages under `Tools > Sonarwhale`.

**Architecture:** `baseUrl` becomes a convention-based entry in `Environment.variables` under the key `baseUrl`, with a pinned UI row in the environment editor. Settings are exposed as IntelliJ `Configurable` instances registered under `parentId="tools"`, replacing the existing `DialogWrapper`-based popup.

**Tech Stack:** Kotlin, IntelliJ Platform Configurable API (`com.intellij.openapi.options.Configurable`), `ShowSettingsUtil`

---

## Section 1: Data Model

`State.baseUrl: String` is removed from `SonarwhaleStateService.State`. No migration — existing users set `baseUrl` manually in their environment variables.

`baseUrl` is stored as `variables["baseUrl"]` inside an `Environment`. The `Environment` data class is unchanged.

All call sites that previously read `stateService.baseUrl` switch to:
```kotlin
stateService.getActiveEnvironment()?.variables?.get("baseUrl") ?: ""
```

The `baseUrl` property and its setter on `SonarwhaleStateService` are removed.

---

## Section 2: RequestPanel URL Bar

The `baseUrlField` text field and its "Base URL" label are removed from `RequestPanel`.

`updateComputedUrl()` reads the base URL from the active environment:
```kotlin
val base = SonarwhaleStateService.getInstance(project)
    .getActiveEnvironment()?.variables?.get("baseUrl") ?: ""
```

The computed URL is: `base + route + queryParams` (same logic as before, different source).

When the active environment changes (user switches env combo), `updateComputedUrl()` is called to refresh. The `baseUrlField.document` listener is removed; no other changes to the request panel layout.

---

## Section 3: Environment Editor — Pinned Base URL Row

`EnvironmentSettingsPanel` is **deleted** (its logic moves to `SonarwhaleEnvironmentsConfigurable`).

In the new environment editor, the UI shows:
- A labeled "Base URL" text field at the top (always visible, not part of the variables table)
- Below: the existing add/remove variable table, which excludes the `baseUrl` key from display

On load: `baseUrlField.text = env.variables["baseUrl"] ?: ""`
On save: `variables["baseUrl"] = baseUrlField.text.trim()` (written before persisting)

If the text field is blank, `baseUrl` is not added to the variables map.

---

## Section 4: IDE Settings — Configurable Classes

Three classes registered in `plugin.xml` under `parentId="tools"`:

```xml
<projectConfigurable parentId="tools"
    instance="com.sonarwhale.settings.SonarwhaleConfigurable"
    displayName="Sonarwhale"
    id="com.sonarwhale.settings.SonarwhaleConfigurable"/>

<projectConfigurable parentId="com.sonarwhale.settings.SonarwhaleConfigurable"
    instance="com.sonarwhale.settings.SonarwhaleSourcesConfigurable"
    displayName="Sources"/>

<projectConfigurable parentId="com.sonarwhale.settings.SonarwhaleConfigurable"
    instance="com.sonarwhale.settings.SonarwhaleEnvironmentsConfigurable"
    displayName="Environments"/>
```

### `SonarwhaleConfigurable`
- Parent node. `createComponent()` returns a real `JPanel` (initially a brief description label) so content can be added later.
- `isModified()` returns `false`, `apply()` and `reset()` are no-ops for now.
- Takes `Project` via constructor (registered as `projectConfigurable`).

### `SonarwhaleSourcesConfigurable`
- Owns all sources UI (logic moved from `OpenApiSourcesPanel`).
- `createComponent()` builds the panel.
- `isModified()` compares current UI state to persisted state.
- `apply()` commits changes to `EnvironmentService`.
- `reset()` reloads from `EnvironmentService`.
- `OpenApiSourcesPanel` is deleted.

### `SonarwhaleEnvironmentsConfigurable`
- Owns all environment + variable UI (logic moved from `EnvironmentSettingsPanel`).
- Pinned Base URL field + variables table as described in Section 3.
- `isModified()` compares working copy to persisted environments.
- `apply()` commits to `SonarwhaleStateService`.
- `reset()` reloads from `SonarwhaleStateService`.
- `EnvironmentSettingsPanel` is deleted.

### Settings Button
`SonarwhaleSettingsDialog` is deleted.

The gear button in `SonarwhalePanel` is updated to:
```kotlin
ShowSettingsUtil.getInstance().showSettingsDialog(project, SonarwhaleConfigurable::class.java)
```

After the settings dialog closes, `refreshEnvCombo()` and `RouteIndexService.refresh()` are called unconditionally — `showSettingsDialog` has no OK/Cancel return value unlike `DialogWrapper.showAndGet()`, so refresh always runs when control returns. A no-op refresh is harmless.

---

## Files Changed

| File | Change |
|---|---|
| `SonarwhaleStateService.kt` | Remove `baseUrl` field from `State` and property |
| `RequestPanel.kt` | Remove `baseUrlField`, update `updateComputedUrl()` |
| `SonarwhalePanel.kt` | Update gear button to use `ShowSettingsUtil` |
| `EnvironmentSettingsPanel.kt` | **Deleted** |
| `OpenApiSourcesPanel.kt` | **Deleted** |
| `SonarwhaleSettingsDialog.kt` | **Deleted** |
| `settings/SonarwhaleConfigurable.kt` | **New** |
| `settings/SonarwhaleSourcesConfigurable.kt` | **New** |
| `settings/SonarwhaleEnvironmentsConfigurable.kt` | **New** |
| `plugin.xml` | Register three configurables |
