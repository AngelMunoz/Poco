package com.poco.api.security

import com.poco.api.config.PocoProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

data class DemoIdentity(val name: String, val permissions: Set<String>)

/** API key to identity, built once from configuration. Shared by HTTP and MCP. */
@Component
class DemoIdentityStore(properties: PocoProperties) {
    private val byKey: Map<String, DemoIdentity> = properties.demoIdentities.mapValues { (_, props) ->
        DemoIdentity(props.name, props.permissions.toSet())
    }

    fun find(apiKey: String?): DemoIdentity? = apiKey?.let { byKey[it] }
}

/**
 * DEMO authentication only: an X-Api-Key header mapped to permission
 * authorities. Replace this filter with JWT/OIDC resource-server support in
 * production and every route rule below keeps working, because authorization
 * only ever looks at the resulting authorities.
 */
@Component
class ApiKeyAuthFilter(private val identities: DemoIdentityStore) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val key = request.getHeader(HEADER)
        if (key != null) {
            val identity = identities.find(key)
            if (identity == null) {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                return
            }
            val authorities = identity.permissions.map { SimpleGrantedAuthority(it) }
            SecurityContextHolder.getContext().authentication =
                UsernamePasswordAuthenticationToken(identity.name, null, authorities)
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-Api-Key"
    }
}

@Configuration
class SecurityConfig(private val apiKeyAuthFilter: ApiKeyAuthFilter) {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .exceptionHandling { handling ->
                handling
                    // Set the status instead of sendError: sendError starts an
                    // /error dispatch that would lose the API-key authentication
                    // and mask a 403 as a 401.
                    .authenticationEntryPoint { _, response, _ -> response.status = HttpServletResponse.SC_UNAUTHORIZED }
                    .accessDeniedHandler { _, response, _ -> response.status = HttpServletResponse.SC_FORBIDDEN }
            }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers(HttpMethod.POST, "/quotes").hasAuthority(Permissions.QUOTES_CREATE)
                    .requestMatchers(HttpMethod.POST, "/claims").hasAuthority(Permissions.CLAIMS_TRIAGE)
                    .requestMatchers(HttpMethod.POST, "/claims/approve").hasAuthority(Permissions.CLAIMS_APPROVE)
                    .requestMatchers(HttpMethod.POST, "/claims/schedule-review").hasAuthority(Permissions.CLAIMS_TRIAGE)
                    .requestMatchers(HttpMethod.GET, "/claims/decisions", "/claims/approvals", "/claims/review-jobs").hasAuthority(Permissions.CLAIMS_READ)
                    .requestMatchers(HttpMethod.GET, "/policies", "/policies/*/verify").hasAuthority(Permissions.POLICIES_READ)
                    .requestMatchers(HttpMethod.POST, "/policies/issue").hasAuthority(Permissions.POLICIES_ISSUE)
                    .requestMatchers(HttpMethod.GET, "/policies/*/renewal").hasAuthority(Permissions.RENEWALS_ASSESS)
                    .requestMatchers("/mcp", "/mcp/**").hasAuthority(Permissions.MCP_USE)
                    // API docs are public on purpose (spec + Swagger UI for testing);
                    // every business route below still requires its permission.
                    .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                    // Secure by default: anything new still needs some caller.
                    .requestMatchers("/error").permitAll()
                    .anyRequest().authenticated()
            }
            .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter::class.java)

        return http.build()
    }
}
