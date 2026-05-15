package dev.sonarwhale.testapi.model;
public record OrderItem(int productId, String productName, int quantity, double unitPrice) {}
