package com.system.resume.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Centralized global CORS configuration for the Intelligent Resume Screening System.
 *
 * Configures Cross-Origin Resource Sharing (CORS) across all application endpoints
 * to permit HTTP communication from recruiter frontends with explicit allowed origins
 * and credentials support. Wildcard origins are strictly prohibited with credentials.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public CorsConfig(@Value("${cors.allowed-origins:http://localhost:5500,http://127.0.0.1:5500}") String allowedOriginsStr) {
        if (allowedOriginsStr != null && !allowedOriginsStr.isBlank()) {
            this.allowedOrigins = Arrays.stream(allowedOriginsStr.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toArray(String[]::new);
        } else {
            this.allowedOrigins = new String[]{"http://localhost:5500", "http://127.0.0.1:5500"};
        }
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Accept", "Authorization", "X-Requested-With")
                .exposedHeaders("Content-Type", "Authorization", "Set-Cookie")
                .allowCredentials(true)
                .maxAge(3600);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Content-Type", "Accept", "Authorization", "X-Requested-With"));
        configuration.setExposedHeaders(List.of("Content-Type", "Authorization", "Set-Cookie"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
