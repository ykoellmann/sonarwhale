package dev.sonarwhale.testapi.model;
public record CreateUserRequest(String username, String email, String password, String role) {}
