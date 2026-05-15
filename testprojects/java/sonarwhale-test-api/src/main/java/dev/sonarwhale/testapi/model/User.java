package dev.sonarwhale.testapi.model;
import java.time.Instant;
public record User(int id, String username, String email, String role, boolean isActive, Instant createdAt) {}
