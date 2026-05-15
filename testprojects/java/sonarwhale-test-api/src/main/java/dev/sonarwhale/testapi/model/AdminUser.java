package dev.sonarwhale.testapi.model;
import java.time.Instant;
public record AdminUser(int id, String username, String email, String role, boolean isActive, boolean isBanned, Instant createdAt, Instant lastLoginAt) {}
