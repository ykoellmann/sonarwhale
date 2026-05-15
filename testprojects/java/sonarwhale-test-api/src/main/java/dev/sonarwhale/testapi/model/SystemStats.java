package dev.sonarwhale.testapi.model;
import java.time.Instant;
public record SystemStats(long totalUsers, long activeUsers, long totalProducts, long totalOrders, double totalRevenue, Instant generatedAt) {}
