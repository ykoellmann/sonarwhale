package dev.sonarwhale.testapi.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Health + public endpoints — no auth required.
 *
 * JavaScanner coverage:
 *   @GetMapping("/health")      → GET /health
 *   @GetMapping("/api/version") → GET /api/version
 *   @RequestMapping(value="/api/users", method=RequestMethod.OPTIONS)
 *                               → OPTIONS /api/users  (old-style @RequestMapping for OPTIONS)
 */
@RestController
@Tag(name = "Public", description = "Public endpoints — no auth required")
public class HealthController {

    @Operation(summary = "Health check — no auth required. Tests inherit.off Sonarwhale pattern.")
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status",    "healthy",
                "version",   "1.0.0",
                "timestamp", Instant.now().toString()));
    }

    @Operation(summary = "API version and runtime information.")
    @GetMapping("/api/version")
    public ResponseEntity<Map<String, String>> version() {
        return ResponseEntity.ok(Map.of(
                "version",  "1.0.0",
                "commit",   "abc1234",
                "builtAt",  "2026-01-01T00:00:00Z",
                "javaVersion", System.getProperty("java.version"),
                "springBoot",  "3.5.0"));
    }

    @Operation(summary = "CORS preflight — tests OPTIONS HTTP method in Sonarwhale.")
    @RequestMapping(value = "/api/users", method = RequestMethod.OPTIONS)
    public ResponseEntity<Void> optionsUsers() {
        return ResponseEntity.ok()
                .header("Allow", "GET, POST, OPTIONS, HEAD")
                .header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                .header("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Key")
                .build();
    }
}
