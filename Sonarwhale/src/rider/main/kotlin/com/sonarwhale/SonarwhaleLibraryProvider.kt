package com.sonarwhale

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.sonarwhale.script.SonarwhaleScriptService
import java.util.Objects

/**
 * Registers .sonarwhale/scripts/ as a synthetic library so IntelliJ indexes
 * sw.d.ts and makes its 'declare const sw' available for autocomplete in all
 * .js scripts, even though the directory is outside the .NET project structure.
 */
class SonarwhaleLibraryProvider : AdditionalLibraryRootsProvider() {

    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        val scriptsRoot = SonarwhaleScriptService.getInstance(project).getScriptsRoot()
        val vf = LocalFileSystem.getInstance().findFileByNioFile(scriptsRoot)
            ?: return emptyList()
        if (!vf.isDirectory) return emptyList()
        return listOf(ScriptLibrary(vf))
    }

    private class ScriptLibrary(private val root: VirtualFile) : SyntheticLibrary() {
        override fun getSourceRoots(): Collection<VirtualFile> = listOf(root)
        override fun equals(other: Any?) = other is ScriptLibrary && root == other.root
        override fun hashCode() = Objects.hash(ScriptLibrary::class, root)
    }
}
