package com.opsflow.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opsflow.auth.JwtAuthenticationFilter;
import com.opsflow.common.ApiResponse;
import com.opsflow.security.OpsFlowSecurityProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public SecurityFilterChain apiSecurity(HttpSecurity http, JwtAuthenticationFilter jwtAuthenticationFilter,
                                           OpsFlowSecurityProperties properties,
                                           ObjectMapper objectMapper) throws Exception {
        return http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
                    auth.requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico",
                                "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/alert-ingest/**",
                                "/status", "/api/v1/status-page", "/api/v1/status-page/**",
                                "/docs", "/docs/**", "/swagger-ui/**", "/api-docs", "/api-docs/**",
                                "/actuator/health").permitAll();
                    if (properties.isPublicMetrics()) {
                        auth.requestMatchers("/actuator/prometheus").permitAll();
                    }
                    auth.anyRequest().authenticated();
                })
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> {
                            response.setStatus(401);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getWriter(),
                                    new ApiResponse<>(false, null, "请先登录或重新登录"));
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            response.setStatus(403);
                            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getWriter(),
                                    new ApiResponse<>(false, null, "当前账号没有此操作权限"));
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource(OpsFlowSecurityProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        String rawOrigins = properties.getCorsAllowedOrigins();
        if (rawOrigins == null || rawOrigins.isBlank()) return source;

        List<String> origins = Arrays.stream(rawOrigins.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank() && !value.equals("*"))
                .distinct()
                .toList();
        if (origins.isEmpty()) return source;

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "X-Trace-Id", "X-Span-Id", "Server-Timing",
                "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Reset", "Retry-After"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}
