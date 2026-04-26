package com.sonarwhale.model

import java.util.UUID

data class ApiCollection(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val environments: List<CollectionEnvironment> = emptyList(),
    val activeEnvironmentId: String? = null,
    val config: HierarchyConfig = HierarchyConfig()
)

data class CollectionEnvironment(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val source: EnvironmentSource
)
