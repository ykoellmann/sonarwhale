package com.sonarwhale.script

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile

/**
 * Resolves the ordered list of pre/post script files for a given endpoint + request.
 * Scripts live in a directory hierarchy rooted at [scriptsRoot].
 *
 * Pre-chain: global → collection → tag → endpoint → request
 * Post-chain: request → endpoint → tag → collection → global  (reversed)
 *
 * inherit.off at any level stops all parent levels from being included.
 */
class ScriptChainResolver(private val scriptsRoot: Path) {

    fun resolvePreChain(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        collectionId: String = "",
        disabledLevels: Set<ScriptLevel> = emptySet()
    ): List<ScriptFile> =
        buildChain(tag, method, path, requestName, ScriptPhase.PRE, collectionId, disabledLevels)

    fun resolvePostChain(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        collectionId: String = "",
        disabledLevels: Set<ScriptLevel> = emptySet()
    ): List<ScriptFile> =
        buildChain(tag, method, path, requestName, ScriptPhase.POST, collectionId, disabledLevels).reversed()

    /** Returns all enabled, inheritance-included levels as (ScriptLevel, ScriptFile?) pairs.
     *  ScriptFile is null when no script exists at that level. Used to emit per-level warnings. */
    fun resolvePreLevels(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        collectionId: String = "",
        disabledLevels: Set<ScriptLevel> = emptySet()
    ): List<Pair<ScriptLevel, ScriptFile?>> =
        buildLeveledChain(tag, method, path, requestName, ScriptPhase.PRE, collectionId, disabledLevels)

    fun resolvePostLevels(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        collectionId: String = "",
        disabledLevels: Set<ScriptLevel> = emptySet()
    ): List<Pair<ScriptLevel, ScriptFile?>> =
        buildLeveledChain(tag, method, path, requestName, ScriptPhase.POST, collectionId, disabledLevels).reversed()

    private fun buildChain(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        phase: ScriptPhase,
        collectionId: String = "",
        disabledLevels: Set<ScriptLevel> = emptySet()
    ): List<ScriptFile> {
        if (!scriptsRoot.exists()) return emptyList()

        val endpointDirName = sanitizeEndpointDir(method, path)
        val requestDirName  = sanitizeName(requestName)
        val tagDirName      = sanitizeName(tag)
        val fileName        = if (phase == ScriptPhase.PRE) "pre.js" else "post.js"

        data class Level(val dir: Path, val level: ScriptLevel)

        val levels = buildList {
            add(Level(scriptsRoot, ScriptLevel.GLOBAL))
            if (collectionId.isNotBlank()) {
                add(Level(scriptsRoot.resolve("collections").resolve(collectionId), ScriptLevel.COLLECTION))
            }
            add(Level(scriptsRoot.resolve(tagDirName), ScriptLevel.TAG))
            add(Level(scriptsRoot.resolve(tagDirName).resolve(endpointDirName), ScriptLevel.ENDPOINT))
            add(Level(scriptsRoot.resolve(tagDirName).resolve(endpointDirName).resolve(requestDirName), ScriptLevel.REQUEST))
        }

        // Deepest inherit.off wins (more specific level takes precedence over broader parent).
        // E.g. inherit.off at ENDPOINT level excludes GLOBAL+TAG even if TAG also has inherit.off.
        val deepestInheritOff = levels.indexOfLast { it.dir.resolve("inherit.off").exists() }

        val includedLevels = if (deepestInheritOff == -1) {
            levels
        } else {
            levels.drop(deepestInheritOff)
        }

        return includedLevels.mapNotNull { (dir, level) ->
            if (level in disabledLevels) return@mapNotNull null
            val scriptFile = dir.resolve(fileName)
            if (scriptFile.exists() && scriptFile.isRegularFile()) {
                ScriptFile(level = level, phase = phase, path = scriptFile)
            } else null
        }
    }

    private fun buildLeveledChain(
        tag: String,
        method: String,
        path: String,
        requestName: String,
        phase: ScriptPhase,
        collectionId: String,
        disabledLevels: Set<ScriptLevel>
    ): List<Pair<ScriptLevel, ScriptFile?>> {
        if (!scriptsRoot.exists()) return emptyList()

        val endpointDirName = sanitizeEndpointDir(method, path)
        val requestDirName  = sanitizeName(requestName)
        val tagDirName      = sanitizeName(tag)
        val fileName        = if (phase == ScriptPhase.PRE) "pre.js" else "post.js"

        data class Level(val dir: Path, val level: ScriptLevel)

        val levels = buildList {
            add(Level(scriptsRoot, ScriptLevel.GLOBAL))
            if (collectionId.isNotBlank()) {
                add(Level(scriptsRoot.resolve("collections").resolve(collectionId), ScriptLevel.COLLECTION))
            }
            add(Level(scriptsRoot.resolve(tagDirName), ScriptLevel.TAG))
            add(Level(scriptsRoot.resolve(tagDirName).resolve(endpointDirName), ScriptLevel.ENDPOINT))
            add(Level(scriptsRoot.resolve(tagDirName).resolve(endpointDirName).resolve(requestDirName), ScriptLevel.REQUEST))
        }

        val deepestInheritOff = levels.indexOfLast { it.dir.resolve("inherit.off").exists() }
        val includedLevels = if (deepestInheritOff == -1) levels else levels.drop(deepestInheritOff)

        return includedLevels
            .filter { (_, level) -> level !in disabledLevels }
            .map { (dir, level) ->
                val scriptFile = dir.resolve(fileName)
                val sf = if (scriptFile.exists() && scriptFile.isRegularFile())
                    ScriptFile(level = level, phase = phase, path = scriptFile)
                else null
                Pair(level, sf)
            }
    }

    companion object {
        fun sanitizeName(name: String): String =
            name.trim().replace(' ', '_').replace('/', '_').trimStart('_')

        fun sanitizeEndpointDir(method: String, path: String): String {
            val sanitizedPath = path.trimStart('/').replace('/', '_')
            return "${method.uppercase()}__${sanitizedPath}"
        }
    }
}
