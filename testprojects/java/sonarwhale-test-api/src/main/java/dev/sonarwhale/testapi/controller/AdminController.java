package dev.sonarwhale.testapi.controller;

import dev.sonarwhale.testapi.model.AdminUser;
import dev.sonarwhale.testapi.model.BanUserRequest;
import dev.sonarwhale.testapi.model.SystemStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Admin controller — Basic auth required.
 *
 * JavaScanner coverage:
 *   @GetMapping("/users")                → GET    /api/admin/users
 *   @PostMapping("/users/{id}/ban")      → POST   /api/admin/users/{id}/ban
 *   @DeleteMapping("/users/{id}")        → DELETE /api/admin/users/{id}
 *   @GetMapping("/stats")                → GET    /api/admin/stats
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Admin operations — Basic auth (admin:admin123)")
public class AdminController {

    private static final List<AdminUser> STORE = new ArrayList<>(List.of(
            new AdminUser(1, "admin",    "admin@example.com",    "admin",    true,  false, Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-05-14T10:00:00Z")),
            new AdminUser(2, "alice",    "alice@example.com",    "user",     true,  false, Instant.parse("2026-02-01T00:00:00Z"), Instant.parse("2026-05-13T08:00:00Z")),
            new AdminUser(3, "bob",      "bob@example.com",      "user",     true,  false, Instant.parse("2026-03-01T00:00:00Z"), null),
            new AdminUser(4, "badactor", "bad@example.com",      "user",     false, true,  Instant.parse("2025-06-01T00:00:00Z"), null)
    ));

    private boolean isBasicAuth(jakarta.servlet.http.HttpServletRequest req) {
        var auth = req.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) return false;
        try {
            var decoded = new String(Base64.getDecoder().decode(auth.substring(6)));
            return "admin:admin123".equals(decoded);
        } catch (Exception e) { return false; }
    }

    @Operation(summary = "Admin view of all users including banned accounts.")
    @GetMapping("/users")
    public ResponseEntity<?> getUsers(
            @RequestParam(defaultValue = "true") boolean includeBanned,
            jakarta.servlet.http.HttpServletRequest req) {
        if (!isBasicAuth(req)) return ResponseEntity.status(401).body(Map.of("message", "Basic auth required"));
        var result = includeBanned ? STORE : STORE.stream().filter(u -> !u.isBanned()).toList();
        return ResponseEntity.ok(result);
    }

    @Operation(summary = "Ban a user account.")
    @PostMapping("/users/{id}/ban")
    public ResponseEntity<?> banUser(
            @PathVariable int id,
            @RequestBody BanUserRequest request,
            jakarta.servlet.http.HttpServletRequest req) {
        if (!isBasicAuth(req)) return ResponseEntity.status(401).body(Map.of("message", "Basic auth required"));
        int idx = findIdx(id);
        if (idx < 0) return ResponseEntity.status(404).body(Map.of("message", "User " + id + " not found"));
        var u = STORE.get(idx);
        STORE.set(idx, new AdminUser(u.id(), u.username(), u.email(), u.role(), false, true, u.createdAt(), u.lastLoginAt()));
        return ResponseEntity.ok(Map.of("message", "User " + id + " banned", "reason", request.reason(),
                "banUntil", request.banUntil() != null ? request.banUntil().toString() : "permanent"));
    }

    @Operation(summary = "Permanently delete a user (hard delete).")
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(
            @PathVariable int id,
            jakarta.servlet.http.HttpServletRequest req) {
        if (!isBasicAuth(req)) return ResponseEntity.status(401).body(Map.of("message", "Basic auth required"));
        int idx = findIdx(id);
        if (idx < 0) return ResponseEntity.status(404).body(Map.of("message", "Not found"));
        STORE.remove(idx);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "System statistics and usage metrics.")
    @GetMapping("/stats")
    public ResponseEntity<?> getStats(jakarta.servlet.http.HttpServletRequest req) {
        if (!isBasicAuth(req)) return ResponseEntity.status(401).body(Map.of("message", "Basic auth required"));
        return ResponseEntity.ok(new SystemStats(
                STORE.size(), STORE.stream().filter(AdminUser::isActive).count(),
                4L, 2L, 1459.96, Instant.now()));
    }

    private int findIdx(int id) {
        for (int i = 0; i < STORE.size(); i++) if (STORE.get(i).id() == id) return i;
        return -1;
    }
}
