package com.flab.kleaguemarket.order.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderResponse(
        UUID orderId,
        long playerId,
        Side side,
        OrderStatus status,
        int quantity,
        int filledQuantity,
        int remainingQuantity,
        long limitPrice,
        List<Fill> fills,
        Long avgFillPrice,
        Instant createdAt
) {
}
