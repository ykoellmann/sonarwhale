package com.sonarwhale.script

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText

class ScriptChainResolverTest {

    @TempDir
    lateinit var tempDir: Path

    private fun scriptsRoot(): Path = tempDir.resolve("scripts").also { it.createDirectories() }
    private fun resolver() = ScriptChainResolver(scriptsRoot())

    private fun scriptDir(vararg parts: String): Path =
        scriptsRoot().let { base ->
            parts.fold(base) { acc, part -> acc.resolve(part) }
        }.also { it.createDirectories() }

    private fun pre(vararg dirs: String): Path =
        scriptDir(*dirs).resolve("pre.js").also { it.createFile(); it.writeText("// pre") }

    private fun post(vararg dirs: String): Path =
        scriptDir(*dirs).resolve("post.js").also { it.createFile(); it.writeText("// post") }

    private fun inheritOff(vararg dirs: String) =
        scriptDir(*dirs).resolve("inherit.off").also { it.createFile() }

    @Test
    fun `resolves global pre script`() {
        val globalPre = pre()
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.any { it.path == globalPre && it.level == ScriptLevel.GLOBAL })
    }

    @Test
    fun `resolves tag pre script`() {
        val tagPre = pre("Users")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.any { it.path == tagPre && it.level == ScriptLevel.TAG })
    }

    @Test
    fun `resolves endpoint pre script`() {
        val endpointPre = pre("Users", "GET__api_users")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.any { it.path == endpointPre && it.level == ScriptLevel.ENDPOINT })
    }

    @Test
    fun `resolves request pre script`() {
        val reqPre = pre("Users", "GET__api_users", "Default")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.any { it.path == reqPre && it.level == ScriptLevel.REQUEST })
    }

    @Test
    fun `pre chain order is global to request`() {
        val globalPre = pre()
        val tagPre = pre("Users")
        val endpointPre = pre("Users", "GET__api_users")
        val reqPre = pre("Users", "GET__api_users", "Default")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        val levels = chain.map { it.level }
        assertEquals(
            listOf(ScriptLevel.GLOBAL, ScriptLevel.TAG, ScriptLevel.ENDPOINT, ScriptLevel.REQUEST),
            levels
        )
    }

    @Test
    fun `post chain order is request to global`() {
        val globalPost = post()
        val tagPost = post("Users")
        val endpointPost = post("Users", "GET__api_users")
        val reqPost = post("Users", "GET__api_users", "Default")
        val chain = resolver().resolvePostChain("Users", "GET", "/api/users", "Default")
        val levels = chain.map { it.level }
        assertEquals(
            listOf(ScriptLevel.REQUEST, ScriptLevel.ENDPOINT, ScriptLevel.TAG, ScriptLevel.GLOBAL),
            levels
        )
    }

    @Test
    fun `inherit off at tag level stops parent scripts`() {
        val globalPre = pre()
        inheritOff("Users")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL })
    }

    @Test
    fun `inherit off at endpoint level stops tag and global`() {
        val globalPre = pre()
        val tagPre = pre("Users")
        inheritOff("Users", "GET__api_users")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL || it.level == ScriptLevel.TAG })
    }

    @Test
    fun `sanitizes path with braces and slashes`() {
        val endpointPre = pre("Users", "GET__api_users_{id}")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users/{id}", "Default")
        assertTrue(chain.any { it.path == endpointPre })
    }

    @Test
    fun `missing scripts directory returns empty chain`() {
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.isEmpty())
    }

    @Test
    fun `missing script file at a level is skipped silently`() {
        val globalPre = pre() // only global exists
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertEquals(1, chain.size)
        assertEquals(ScriptLevel.GLOBAL, chain[0].level)
    }

    @Test
    fun `inherit off at request level stops all parent scripts`() {
        val globalPre = pre()
        val tagPre = pre("Users")
        val endpointPre = pre("Users", "GET__api_users")
        inheritOff("Users", "GET__api_users", "Default")
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL || it.level == ScriptLevel.TAG || it.level == ScriptLevel.ENDPOINT })
    }

    @Test
    fun `inherit off at deepest level wins when multiple inherit off exist`() {
        val globalPre = pre()
        val tagPre = pre("Users")
        val endpointPre = pre("Users", "GET__api_users")
        inheritOff("Users")          // tag level inherit.off
        inheritOff("Users", "GET__api_users") // endpoint level inherit.off — this is deeper, should win
        val chain = resolver().resolvePreChain("Users", "GET", "/api/users", "Default")
        // endpoint inherit.off wins: TAG and GLOBAL excluded, ENDPOINT included
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL || it.level == ScriptLevel.TAG })
        assertTrue(chain.any { it.level == ScriptLevel.ENDPOINT })
    }

    @Test
    fun `disabled GLOBAL level excludes global pre script`() {
        pre() // global pre.js
        pre("Users") // tag pre.js
        val chain = resolver().resolvePreChain(
            "Users", "GET", "/api/users", "Default",
            disabledLevels = setOf(ScriptLevel.GLOBAL)
        )
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL })
        assertTrue(chain.any { it.level == ScriptLevel.TAG })
    }

    @Test
    fun `disabled TAG level excludes tag pre script but keeps global`() {
        pre() // global
        pre("Users") // tag
        val chain = resolver().resolvePreChain(
            "Users", "GET", "/api/users", "Default",
            disabledLevels = setOf(ScriptLevel.TAG)
        )
        assertTrue(chain.none { it.level == ScriptLevel.TAG })
        assertTrue(chain.any { it.level == ScriptLevel.GLOBAL })
    }

    @Test
    fun `multiple disabled levels excludes all specified`() {
        pre() // global
        pre("Users") // tag
        pre("Users", "GET__api_users") // endpoint
        val chain = resolver().resolvePreChain(
            "Users", "GET", "/api/users", "Default",
            disabledLevels = setOf(ScriptLevel.GLOBAL, ScriptLevel.TAG)
        )
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL })
        assertTrue(chain.none { it.level == ScriptLevel.TAG })
        assertTrue(chain.any { it.level == ScriptLevel.ENDPOINT })
    }

    @Test
    fun `disabled levels also filter post chain`() {
        post() // global
        post("Users") // tag
        val chain = resolver().resolvePostChain(
            "Users", "GET", "/api/users", "Default",
            disabledLevels = setOf(ScriptLevel.GLOBAL)
        )
        assertTrue(chain.none { it.level == ScriptLevel.GLOBAL })
        assertTrue(chain.any { it.level == ScriptLevel.TAG })
    }

    @Test
    fun `empty disabledLevels returns full chain`() {
        pre() // global
        pre("Users") // tag
        val chain = resolver().resolvePreChain(
            "Users", "GET", "/api/users", "Default",
            disabledLevels = emptySet()
        )
        assertEquals(2, chain.size)
    }
}
