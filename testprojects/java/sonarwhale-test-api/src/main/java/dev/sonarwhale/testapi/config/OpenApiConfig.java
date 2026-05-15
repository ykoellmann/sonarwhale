package dev.sonarwhale.testapi.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Sonarwhale Test API (Java / Spring Boot)")
                        .version("1.0.0")
                        .description("Comprehensive test API covering all Sonarwhale features: " +
                                "all HTTP methods, all parameter locations (path/query/header/cookie), " +
                                "all auth types (Bearer JWT / API Key / Basic / OAuth2), " +
                                "complex request bodies, and scanner edge cases for JavaScanner."))
                .components(new Components()
                        .addSecuritySchemes("Bearer", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT Bearer. Get token via POST /api/auth/login"))
                        .addSecuritySchemes("ApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")
                                .description("API key for product endpoints. Test value: test-api-key-12345"))
                        .addSecuritySchemes("BasicAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Basic auth for admin endpoints. Credentials: admin:admin123"))
                        .addSecuritySchemes("OAuth2", new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .flows(new OAuthFlows()
                                        .clientCredentials(new OAuthFlow()
                                                .tokenUrl("/api/auth/login")
                                                .scopes(new Scopes()
                                                        .addString("orders:read",  "Read orders")
                                                        .addString("orders:write", "Write orders"))))));
    }
}
