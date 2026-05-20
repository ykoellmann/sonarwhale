package com.sonarwhale.service

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe

object SecretStorageService {
    fun get(projectHash: String, key: String): String? =
        PasswordSafe.instance.getPassword(attrs(projectHash, key))

    fun set(projectHash: String, key: String, value: String) =
        PasswordSafe.instance.set(attrs(projectHash, key), Credentials(key, value))

    fun remove(projectHash: String, key: String) =
        PasswordSafe.instance.set(attrs(projectHash, key), null)

    private fun attrs(projectHash: String, key: String) =
        CredentialAttributes(generateServiceName("Sonarwhale/$projectHash", key))
}
