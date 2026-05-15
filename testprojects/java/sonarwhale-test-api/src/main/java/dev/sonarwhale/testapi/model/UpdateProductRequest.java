package dev.sonarwhale.testapi.model;
public record UpdateProductRequest(String name, String description, Double price, String category, Integer stockCount) {}
