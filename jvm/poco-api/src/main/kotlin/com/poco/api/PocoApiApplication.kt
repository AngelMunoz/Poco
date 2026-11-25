package com.poco.api

import com.poco.api.config.PocoProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(PocoProperties::class)
class PocoApiApplication

fun main(args: Array<String>) {
    runApplication<PocoApiApplication>(*args)
}
