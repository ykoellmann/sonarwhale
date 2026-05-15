package dev.sonarwhale.testapi.model;
import java.time.Instant;
import java.util.List;
public record Order(int id, int userId, List<OrderItem> items, ShippingAddress shippingAddress, String status, double totalAmount, Instant createdAt) {}
