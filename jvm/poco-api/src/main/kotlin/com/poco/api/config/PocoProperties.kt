package com.poco.api.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.math.BigDecimal

@ConfigurationProperties(prefix = "poco")
data class PocoProperties(
    val tariffs: Map<String, BigDecimal> = emptyMap(),
    val demoIdentities: Map<String, DemoIdentityProps> = emptyMap(),
)

data class DemoIdentityProps(
    val name: String = "",
    val permissions: List<String> = emptyList(),
)
