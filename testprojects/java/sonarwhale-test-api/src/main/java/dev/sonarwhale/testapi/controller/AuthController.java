package dev.sonarwhale.testapi.controller;

import dev.sonarwhale.testapi.model.LoginRequest;
import dev.sonarwhale.testapi.model.RefreshRequest;
import dev.sonarwhale.testapi.model.TokenResponse;
import dev.sonarwhale.testapi.security.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Auth controller — no authentication required.
 *
 * JavaScanner coverage:
 *   @PostMapping("/login")   → POST /api/auth/login
 *   @PostMapping("/refresh") → POST /api/auth/refresh
 *   @PostMapping("/logout")  → POST /api/auth/logout
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Auth", description = "Authentication — no auth required")
public class AuthController {

    private static final Map<String, String[]> USERS = Map.of(
            "admin",    new String[]{"admin123",    "admin"},
            "user",     new String[]{"user123",     "user"},
            "readonly", new String[]{"readonly123", "readonly"}
    );

    private final JwtUtil jwtUtil;

    public AuthController(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Operation(summary = "Login with username + password. Returns JWT access and refresh token.")
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        var user = USERS.get(request.username());
        if (user == null || !user[0].equals(request.password())) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid credentials"));
        }
        return ResponseEntity.ok(new TokenResponse(
                jwtUtil.generateToken(request.username(), user[1]),
                "refresh_" + request.username() + "_" + System.currentTimeMillis(),
                "bearer",
                3600));
    }

    @Operation(summary = "Exchange a refresh token for a new access token.")
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        if (!request.refreshToken().startsWith("refresh_")) {
            return ResponseEntity.status(401).body(Map.of("message", "Invalid refresh token"));
        }
        var parts    = request.refreshToken().split("_");
        var username = parts.length > 1 ? parts[1] : "user";
        var role     = USERS.containsKey(username) ? USERS.get(username)[1] : "user";
        return ResponseEntity.ok(new TokenResponse(
                jwtUtil.generateToken(username, role),
                "refresh_" + username + "_" + System.currentTimeMillis(),
                "bearer",
                3600));
    }

    @Operation(summary = "Invalidate the current session. Returns 204 No Content.")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        // In a real app: add token to deny-list
        return ResponseEntity.noContent().build();
    }
}
