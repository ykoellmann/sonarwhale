package dev.sonarwhale.testapi.model;
public record ProductVariant(int id, int productId, String sku, String size, String color, double priceModifier, int stockCount) {}
