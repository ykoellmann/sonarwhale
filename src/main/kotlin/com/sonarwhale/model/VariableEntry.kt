package com.sonarwhale.model

data class VariableEntry(
    val key: String = "",
    val value: String = "",    // always "" when isSecret = true
    val enabled: Boolean = true,
    val isSecret: Boolean = false
)