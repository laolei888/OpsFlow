package com.opsflow.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.Set;

@Configuration
public class OpenApiConfig {
    public static final String BEARER_AUTH = "bearerAuth";

    private static final Set<String> PUBLIC_EXACT_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/register",
            "/api/v1/status-page"
    );

    @Bean
    public OpenAPI opsFlowOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OpsFlow API")
                        .version("v1")
                        .description("企业级工单、知识库与 IT 服务管理协同平台 API。"
                                + "除公开状态页、登录、注册和告警接入外，接口均使用 JWT Bearer Token。")
                        .contact(new Contact().name("OpsFlow Engineering")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH,
                        new SecurityScheme()
                                .name(BEARER_AUTH)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
    }

    @Bean
    public OpenApiCustomizer publicEndpointSecurityCustomizer() {
        return openApi -> openApi.getPaths().forEach((path, pathItem) -> {
            if (isPublicPath(path)) {
                pathItem.readOperations().forEach(operation -> operation.setSecurity(Collections.emptyList()));
            }
        });
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_EXACT_PATHS.contains(path)
                || path.startsWith("/api/v1/status-page/")
                || path.startsWith("/api/v1/alert-ingest/");
    }
}
