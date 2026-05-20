package com.sonarwhale.model

data class EndpointConfig(
    val endpointId: String = "",
    val config: HierarchyConfig = HierarchyConfig()
)
