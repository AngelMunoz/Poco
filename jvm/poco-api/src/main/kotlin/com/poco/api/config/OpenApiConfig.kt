package com.poco.api.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Names the generated OpenAPI document; routes/schemas come from springdoc scanning the controllers. */
@Configuration
class OpenApiConfig {

    @Bean
    fun pocoOpenApi(): OpenAPI = OpenAPI().info(
        Info()
            .title("Poco API (JVM)")
            .version("1.0.0")
            .description("Kotlin/Spring Boot counterpart of the Poco demo. Business routes need an X-Api-Key header; this document and Swagger UI are public for testing."),
    )
}
