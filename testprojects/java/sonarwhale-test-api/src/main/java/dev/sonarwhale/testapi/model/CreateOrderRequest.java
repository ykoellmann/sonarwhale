package dev.sonarwhale.testapi.model;
import java.util.List;
public record CreateOrderRequest(List<CreateOrderItemRequest> items, ShippingAddress shippingAddress) {}
