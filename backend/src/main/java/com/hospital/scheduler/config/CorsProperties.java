package com.hospital.scheduler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configurable CORS settings. Production deployments MUST override
 * {@code app.cors.allowed-origins} with the real frontend host; the
 * default localhost entries are for local dev only.
 *
 * Example (application-prod.properties):
 *   app.cors.allowed-origins=https://medschedule.example.com
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(
        List<String> allowedOrigins,
        List<String> allowedOriginPatterns
) {
    public CorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            // Safe default for local dev — three known frontend ports.
            allowedOrigins = List.of(
                    "http://localhost:3000",
                    "http://localhost:3001",
                    "http://localhost:5173"
            );
        }
        if (allowedOriginPatterns == null || allowedOriginPatterns.isEmpty()) {
            // Wildcard patterns cover Vercel preview/production deployments
            // (e.g. https://medschedule-pro.vercel.app, https://*-abc123.vercel.app)
            // and the team's own Render static-site frontend. Concrete origins
            // take precedence; these patterns are matched only when an exact
            // match is not found.
            allowedOriginPatterns = List.of(
                    "https://*.vercel.app",
                    "https://*.onrender.com"
            );
        }
    }
}