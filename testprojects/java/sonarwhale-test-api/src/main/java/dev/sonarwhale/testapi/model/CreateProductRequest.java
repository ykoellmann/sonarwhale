package dev.sonarwhale.testapi.model;
public record CreateProductRequest(String name, String description, double price, String category, int stockCount) {}
