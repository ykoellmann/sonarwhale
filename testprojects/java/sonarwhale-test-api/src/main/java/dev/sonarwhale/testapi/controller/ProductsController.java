package dev.sonarwhale.testapi.controller;

import dev.sonarwhale.testapi.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Products controller — API Key auth (X-Api-Key header, validated manually).
 *
 * JavaScanner edge cases covered:
 *
 *   @GetMapping                              → GET  /api/products  (no path)
 *   @GetMapping("/search")                   → GET  /api/products/search
 *   @GetMapping("/{id}")                     → GET  /api/products/{id}
 *   @PostMapping(consumes = "...")           → POST /api/products (named 'consumes=' — no path)
 *   @PutMapping("/{id}")                     → PUT  /api/products/{id}
 *   @DeleteMapping("/{id}")                  → DELETE /api/products/{id}
 *   @GetMapping(value = "/{id}/variants",
 *               produces = "application/json") → GET  (multi-line, named 'value=')
 *
 *   @RequestMapping(value="/by-name/{name}",
 *                   method = RequestMethod.GET)  → old-style @RequestMapping
 */
@RestController
@RequestMapping("/api/products")
@Tag(name = "Products", description = "Product catalog — API Key (X-Api-Key header)")
public class ProductsController {

    private static final String VALID_API_KEY = "test-api-key-12345";

    private static final List<Product> STORE = new ArrayList<>(List.of(
            new Product(1, "Laptop Pro 15",      "High-end developer laptop", 1299.99, "electronics", true,  12, Instant.parse("2025-10-01T00:00:00Z")),
            new Product(2, "Mechanical Keyboard", "Clicky switches",           89.99,  "electronics", true,  45, Instant.parse("2025-11-01T00:00:00Z")),
            new Product(3, "Clean Code",          "Book by Robert C. Martin",  34.99,  "books",       true,  200, Instant.parse("2025-09-01T00:00:00Z")),
            new Product(4, "Coffee Beans",        "Ethiopian single origin",   18.50,  "food",        false, 0,  Instant.parse("2026-04-01T00:00:00Z"))
    ));
    private static final AtomicInteger ID_SEQ = new AtomicInteger(5);

    private ResponseEntity<Map<String, String>> checkApiKey(jakarta.servlet.http.HttpServletRequest req) {
        var key = req.getHeader("X-Api-Key");
        return VALID_API_KEY.equals(key) ? null
                : ResponseEntity.status(401).body(Map.of("message", "Invalid or missing X-Api-Key"));
    }

    // ── GET /api/products ─────────────────────────────────────────────────────

    @Operation(summary = "List products with optional filters.")
    @GetMapping
    public ResponseEntity<?> getAll(
            @RequestParam(required = false) String  category,
            @RequestParam(required = false) Double  minPrice,
            @RequestParam(required = false) Double  maxPrice,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "20") int pageSize,
            jakarta.servlet.http.HttpServletRequest req) {
        var err = checkApiKey(req); if (err != null) return (ResponseEntity<?>) err;
        var q = STORE.stream()
                .filter(p -> category == null || p.category().equals(category))
                .filter(p -> minPrice == null || p.price() >= minPrice)
                .filter(p -> maxPrice == null || p.price() <= maxPrice)
                .filter(p -> inStock  == null || p.inStock() == inStock)
                .toList();
        var items = q.stream().skip((long)(page-1)*pageSize).limit(pageSize).toList();
        return ResponseEntity.ok(new PagedResult<>(items, page, pageSize, q.size()));
    }

    // ── GET /api/products/search ──────────────────────────────────────────────

    @Operation(summary = "Full-text product search.")
    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam String q,
            @RequestParam(required = false) List<String> tags,
            @RequestParam(defaultValue = "false") boolean inStock,
            jakarta.servlet.http.HttpServletRequest req) {
        var err = checkApiKey(req); if (err != null) return (ResponseEntity<?>) err;
        var results = STORE.stream()
                .filter(p -> p.name().contains(q) || p.description().contains(q))
                .filter(p -> !inStock || p.inStock())
                .toList();
        return ResponseEntity.ok(results);
    }

    // ── GET /api/products/{id} ────────────────────────────────────────────────

    @Operation(summary = "Get a product by ID.")
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(
            @PathVariable int id,
            jakarta.servlet.http.HttpServletRequest req) {
        var err = checkApiKey(req); if (err != null) return (ResponseEntity<?>) err;
        return STORE.stream().filter(p -> p.id() == id).findFirst()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "Not found")));
    }

    // ── POST /api/products ────────────────────────────────────────────────────
    // consumes= named arg — no path arg → JavaScanner must not false-positive on consumes

    @Operation(summary = "Create a new product.")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> create(
            @RequestBody CreateProductRequest request,
            jakarta.servlet.http.HttpServletRequest req) {
        var err = checkApiKey(req); if (err != null) return (ResponseEntity<?>) err;
        var p = new Product(ID_SEQ.getAndIncrement(), request.name(), request.description(),
                request.price(), request.category(), request.stockCount() > 0,
                request.stockCount(), Instant.now());
        STORE.add(p);
        return ResponseEntity.created(URI.create("/api/products/" + p.id())).body(p);
    }

    // ── PUT /api/products/{id} ────────────────────────────────────────────────

    @Operation(summary = "Full update of a product.")
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @PathVariable int id,
            @RequestBody UpdateProductRequest request,
            jakarta.servlet.http.HttpServletRequest req) {
        var err = checkApiKey(req); if (err != null) return (ResponseEntity<?>) err;
        int idx = findIdx(id);
        if (idx < 0) return ResponseEntity.status(404).body(Map.of("message", "Not found"));
        var e = STORE.get(idx);
        int newStock = request.stockCount() != null ? request.stockCount() : e.stockCount();
        var updated = new Product(id,
                request.name()        != null ? request.name()        : e.name(),
                request.description() != null ? request.description() : e.description(),
                request.price()       != null ? request.price()       : e.price(),
                request.category()    != null ? request.category()    : e.category(),
                newStock > 0, newStock, e.createdAt());
        STORE.set(idx, updated);
        return ResponseEntity.ok(updated);
    }

    // ── DELETE /api/products/{id} ─────────────────────────────────────────────

    @Operation(summary = "Delete a product.")
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @PathVariable int id,
            jakarta.servlet.http.HttpServletRequest req) {
        var err = checkApiKey(req); if (err != null) return (ResponseEntity<?>) err;
        int idx = findIdx(id);
        if (idx < 0) return ResponseEntity.status(404).body(Map.of("message", "Not found"));
        STORE.remove(idx);
        return ResponseEntity.noContent().build();
    }

    // ── GET /api/products/{id}/variants ──────────────────────────────────────
    // Multi-line annotation with named value= and produces= — JavaScanner paren-depth test

    @Operation(summary = "List all variants of a product.")
    @GetMapping(
        value    = "/{id}/variants",
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<?> getVariants(
            @PathVariable int id,
            jakarta.servlet.http.HttpServletRequest req) {
        var err = checkApiKey(req); if (err != null) return (ResponseEntity<?>) err;
        if (STORE.stream().noneMatch(p -> p.id() == id))
            return ResponseEntity.status(404).body(Map.of("message", "Not found"));
        return ResponseEntity.ok(List.of(
                new ProductVariant(1, id, "SKU-" + id + "-SM-BLK", "S", "Black", 0.0,  10),
                new ProductVariant(2, id, "SKU-" + id + "-MD-BLK", "M", "Black", 0.0,  5),
                new ProductVariant(3, id, "SKU-" + id + "-LG-WHT", "L", "White", 5.0,  3)
        ));
    }

    // ── GET /api/products/by-name/{name}  (old-style @RequestMapping) ─────────
    // Tests JavaScanner @RequestMapping with method=RequestMethod.GET

    @Operation(summary = "Get product by name slug (old-style @RequestMapping).")
    @RequestMapping(
        value  = "/by-name/{name}",
        method = RequestMethod.GET
    )
    public ResponseEntity<?> getByName(
            @PathVariable String name,
            jakarta.servlet.http.HttpServletRequest req) {
        var err = checkApiKey(req); if (err != null) return (ResponseEntity<?>) err;
        return STORE.stream()
                .filter(p -> p.name().toLowerCase().replace(" ", "-").equals(name.toLowerCase()))
                .findFirst()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "Not found")));
    }

    private int findIdx(int id) {
        for (int i = 0; i < STORE.size(); i++) if (STORE.get(i).id() == id) return i;
        return -1;
    }
}
