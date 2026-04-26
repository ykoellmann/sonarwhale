package com.sonarwhale.model

data class TagConfig(
    val tag: String = "",
    val config: HierarchyConfig = HierarchyConfig()
)
