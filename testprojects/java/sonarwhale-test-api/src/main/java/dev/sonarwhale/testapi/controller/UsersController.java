package dev.sonarwhale.testapi.controller;

import dev.sonarwhale.testapi.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Users controller — Bearer JWT auth required.
 *
 * JavaScanner edge cases covered:
 *
 *   @GetMapping                    → GET  /api/users  (no path → only prefix)
 *   @GetMapping("/{id}")           → GET  /api/users/{id}
 *   @GetMapping(path = "/search")  → GET  /api/users/search  (named 'path=' arg)
 *   @PostMapping                   → POST /api/users
 *   @PutMapping("/{id}")           → PUT  /api/users/{id}
 *   @PatchMapping("/{id}")         → PATCH /api/users/{id}
 *   @DeleteMapping("/{id}")        → DELETE /api/users/{id}
 *   @GetMapping("/{id:[0-9]+}/avatar")  → GET  /api/users/{id}/avatar  (regex constraint)
 *   @PostMapping("/{id:[0-9]+}/avatar") → POST /api/users/{id}/avatar  (multipart)
 */
@RestController
@RequestMapping("/api/users")
@Tag(name = "Users", description = "User management — Bearer JWT")
public class UsersController {

    private static final List<User> STORE = new ArrayList<>(List.of(
            new User(1, "admin",    "admin@example.com",    "admin",    true,  Instant.parse("2026-01-01T00:00:00Z")),
            new User(2, "alice",    "alice@example.com",    "user",     true,  Instant.parse("2026-02-01T00:00:00Z")),
            new User(3, "bob",      "bob@example.com",      "user",     true,  Instant.parse("2026-03-01T00:00:00Z")),
            new User(4, "inactive", "inactive@example.com", "user",     false, Instant.parse("2025-06-01T00:00:00Z"))
    ));
    private static final AtomicInteger ID_SEQ = new AtomicInteger(5);

    // ── GET /api/users ─────────────────────────────────────────────────────────
    // @GetMapping with no path → only controller prefix used for matching

    @Operation(summary = "List all users (paginated).")
    @GetMapping
    public ResponseEntity<PagedResult<User>> getAll(
            @RequestParam(defaultValue = "1")  int    page,
            @RequestParam(defaultValue = "20") int    pageSize,
            @RequestParam(required = false)    String search,
            @RequestParam(defaultValue = "username") String sort) {
        var q = search == null ? STORE : STORE.stream()
                .filter(u -> u.username().contains(search) || u.email().contains(search))
                .toList();
        var items = q.stream().skip((long) (page - 1) * pageSize).limit(pageSize).toList();
        return ResponseEntity.ok(new PagedResult<>(items, page, pageSize, q.size()));
    }

    // ── GET /api/users/search  (named path= argument) ─────────────────────────
    // Must be declared BEFORE /{id} to avoid ambiguity

    @Operation(summary = "Search users by username or email.")
    @GetMapping(path = "/search")
    public ResponseEntity<List<User>> search(@RequestParam String q) {
        var results = STORE.stream()
                .filter(u -> u.username().contains(q) || u.email().contains(q))
                .toList();
        return ResponseEntity.ok(results);
    }

    // ── GET /api/users/{id} ────────────────────────────────────────────────────

    @Operation(summary = "Get a single user by ID.")
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable int id) {
        return STORE.stream().filter(u -> u.id() == id).findFirst()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "User " + id + " not found")));
    }

    // ── POST /api/users ────────────────────────────────────────────────────────

    @Operation(summary = "Create a new user. Returns 201 with the created resource.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(@RequestBody CreateUserRequest request) {
        if (STORE.stream().anyMatch(u -> u.username().equals(request.username()))) {
            return ResponseEntity.status(409).body(Map.of("message", "Username already taken"));
        }
        var user = new User(ID_SEQ.getAndIncrement(), request.username(), request.email(),
                request.role(), true, Instant.now());
        STORE.add(user);
        return ResponseEntity.created(URI.create("/api/users/" + user.id())).body(user);
    }

    // ── PUT /api/users/{id} ────────────────────────────────────────────────────

    @Operation(summary = "Full update of a user.")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable int id, @RequestBody UpdateUserRequest request) {
        int idx = findIdx(id);
        if (idx < 0) return ResponseEntity.status(404).body(Map.of("message", "Not found"));
        var e = STORE.get(idx);
        var updated = new User(id,
                request.username() != null ? request.username() : e.username(),
                request.email()    != null ? request.email()    : e.email(),
                request.role()     != null ? request.role()     : e.role(),
                e.isActive(), e.createdAt());
        STORE.set(idx, updated);
        return ResponseEntity.ok(updated);
    }

    // ── PATCH /api/users/{id} ──────────────────────────────────────────────────

    @Operation(summary = "Partial update — only provided fields are changed.")
    @PatchMapping("/{id}")
    public ResponseEntity<?> partialUpdate(@PathVariable int id, @RequestBody UpdateUserRequest request) {
        return update(id, request);
    }

    // ── DELETE /api/users/{id} ─────────────────────────────────────────────────

    @Operation(summary = "Delete a user permanently. Returns 204 No Content.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        int idx = findIdx(id);
        if (idx < 0) return ResponseEntity.status(404).build();
        STORE.remove(idx);
        return ResponseEntity.noContent().build();
    }

    // ── GET /api/users/{id:[0-9]+}/avatar  ────────────────────────────────────
    // Regex constraint in path param — JavaScanner must normalise to {id}

    @Operation(summary = "Download user avatar image.")
    @GetMapping("/{id:[0-9]+}/avatar")
    public ResponseEntity<byte[]> getAvatar(
            @PathVariable int id,
            @RequestHeader(value = "Accept", defaultValue = "image/png") String accept) {
        if (STORE.stream().noneMatch(u -> u.id() == id)) {
            return ResponseEntity.status(404).build();
        }
        // 1×1 transparent PNG
        byte[] png = hexToBytes(
                "89504e470d0a1a0a0000000d494844520000000100000001080600000" +
                "01f15c489000000110049444154789c6260f8cfc00000000200017e21" +
                "bc330000000049454e44ae426082");
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("Content-Disposition", "attachment; filename=\"avatar_" + id + ".png\"")
                .body(png);
    }

    // ── POST /api/users/{id:[0-9]+}/avatar  ───────────────────────────────────

    @Operation(summary = "Upload a new avatar for a user (multipart/form-data).")
    @PostMapping(value = "/{id:[0-9]+}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAvatar(
            @PathVariable int id,
            @RequestParam("file") MultipartFile file) throws Exception {
        if (STORE.stream().noneMatch(u -> u.id() == id)) {
            return ResponseEntity.status(404).body(Map.of("message", "Not found"));
        }
        if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("message", "No file provided"));
        var ct = file.getContentType();
        if (ct == null || !ct.startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("message", "File must be an image"));
        }
        return ResponseEntity.ok(Map.of(
                "message",     "Avatar uploaded",
                "size",        file.getSize(),
                "contentType", ct));
    }

    // ── HEAD /api/users/{id} ───────────────────────────────────────────────────

    @Operation(summary = "Check if a user exists (no response body — HEAD method).")
    @RequestMapping(value = "/{id}", method = org.springframework.web.bind.annotation.RequestMethod.HEAD)
    public ResponseEntity<Void> exists(@PathVariable int id) {
        return STORE.stream().anyMatch(u -> u.id() == id)
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(404).build();
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private int findIdx(int id) {
        for (int i = 0; i < STORE.size(); i++) if (STORE.get(i).id() == id) return i;
        return -1;
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
        return data;
    }
}
