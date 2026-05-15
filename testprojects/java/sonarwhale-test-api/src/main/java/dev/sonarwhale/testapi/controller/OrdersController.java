package dev.sonarwhale.testapi.controller;

import dev.sonarwhale.testapi.model.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Orders controller — Bearer JWT (simulating OAuth2 client credentials scope).
 *
 * JavaScanner coverage:
 *   @GetMapping                           → GET    /api/orders
 *   @GetMapping("/{id}")                  → GET    /api/orders/{id}
 *   @PostMapping                          → POST   /api/orders  (complex nested body)
 *   @PatchMapping("/{id}/status")         → PATCH  /api/orders/{id}/status
 *   @DeleteMapping("/{id}")               → DELETE /api/orders/{id}
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Order management — Bearer JWT (OAuth2 client credentials scope)")
public class OrdersController {

    private static final List<Order> STORE = new ArrayList<>(List.of(
            new Order(1, 2,
                    List.of(new OrderItem(1, "Laptop Pro 15",      1, 1299.99),
                            new OrderItem(2, "Mechanical Keyboard", 1, 89.99)),
                    new ShippingAddress("123 Main St", "Springfield", "12345", "US"),
                    "delivered", 1389.98, Instant.parse("2026-03-01T00:00:00Z")),
            new Order(2, 3,
                    List.of(new OrderItem(3, "Clean Code", 2, 34.99)),
                    new ShippingAddress("456 Oak Ave", "Shelbyville", "67890", "US"),
                    "shipped", 69.98, Instant.parse("2026-05-10T00:00:00Z"))
    ));
    private static final AtomicInteger ID_SEQ = new AtomicInteger(3);

    @Operation(summary = "List orders with optional filters.")
    @GetMapping
    public ResponseEntity<PagedResult<Order>> getAll(
            @RequestParam(required = false) String   status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Integer  userId,
            @RequestParam(defaultValue = "1")  int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        var q = STORE.stream()
                .filter(o -> status == null || o.status().equals(status))
                .filter(o -> userId == null || o.userId() == userId)
                .toList();
        var items = q.stream().skip((long)(page-1)*pageSize).limit(pageSize).toList();
        return ResponseEntity.ok(new PagedResult<>(items, page, pageSize, q.size()));
    }

    @Operation(summary = "Get a single order including all line items.")
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable int id) {
        return STORE.stream().filter(o -> o.id() == id).findFirst()
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("message", "Order " + id + " not found")));
    }

    @Operation(summary = "Create a new order. Complex nested body: items[] + shippingAddress{}.")
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateOrderRequest request) {
        if (request.items().isEmpty())
            return ResponseEntity.badRequest().body(Map.of("message", "Order must have at least one item"));
        var items = request.items().stream()
                .map(i -> new OrderItem(i.productId(), "Product " + i.productId(), i.quantity(), 10.0))
                .toList();
        var total = items.stream().mapToDouble(i -> i.quantity() * i.unitPrice()).sum();
        var order = new Order(ID_SEQ.getAndIncrement(), 1, items,
                request.shippingAddress(), "pending", total, Instant.now());
        STORE.add(order);
        return ResponseEntity.created(URI.create("/api/orders/" + order.id())).body(order);
    }

    @Operation(summary = "Update the status of an existing order.")
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(
            @PathVariable int id,
            @RequestBody UpdateOrderStatusRequest request) {
        int idx = findIdx(id);
        if (idx < 0) return ResponseEntity.status(404).body(Map.of("message", "Not found"));
        if ("cancelled".equals(STORE.get(idx).status()))
            return ResponseEntity.badRequest().body(Map.of("message", "Cannot update a cancelled order"));
        var o = STORE.get(idx);
        STORE.set(idx, new Order(o.id(), o.userId(), o.items(), o.shippingAddress(),
                request.status(), o.totalAmount(), o.createdAt()));
        return ResponseEntity.ok(STORE.get(idx));
    }

    @Operation(summary = "Cancel an order (soft-delete). Returns 204 No Content.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@PathVariable int id) {
        int idx = findIdx(id);
        if (idx < 0) return ResponseEntity.status(404).build();
        var o = STORE.get(idx);
        STORE.set(idx, new Order(o.id(), o.userId(), o.items(), o.shippingAddress(),
                "cancelled", o.totalAmount(), o.createdAt()));
        return ResponseEntity.noContent().build();
    }

    private int findIdx(int id) {
        for (int i = 0; i < STORE.size(); i++) if (STORE.get(i).id() == id) return i;
        return -1;
    }
}
