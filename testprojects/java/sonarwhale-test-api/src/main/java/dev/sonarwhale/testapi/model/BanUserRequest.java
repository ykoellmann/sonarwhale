package dev.sonarwhale.testapi.model;
import java.time.Instant;
public record BanUserRequest(String reason, Instant banUntil) {}
