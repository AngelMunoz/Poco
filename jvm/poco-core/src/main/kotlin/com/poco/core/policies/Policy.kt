package com.poco.core.policies

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The only shared kernel in Core: a policy as data. No behaviour, no storage.
 * Each consumer keeps policies wherever it already does (JPA, JDBC, a file,
 * a list) and maps them into this shape.
 */
data class Policy(
    val id: UUID,
    val holderName: String,
    val coverageLimit: BigDecimal,
    val deductible: BigDecimal,
    val expiresOn: LocalDate,
    val isActive: Boolean,
)
