package com.flab.kleaguemarket.order.dto;

import com.flab.kleaguemarket.domain.order.OrderStatus;
import com.flab.kleaguemarket.domain.order.Side;
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
