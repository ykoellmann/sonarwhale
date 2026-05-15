package dev.sonarwhale.testapi.model;
import java.time.Instant;
public record Product(int id, String name, String description, double price, String category, boolean inStock, int stockCount, Instant createdAt) {}
