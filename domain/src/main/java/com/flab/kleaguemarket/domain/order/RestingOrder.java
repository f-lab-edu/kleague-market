package com.flab.kleaguemarket.domain.order;

import java.util.Objects;
import java.util.UUID;

/**
 * 호가창에 남아 있는 주문 중 매칭이 보는 부분만.
 *
 * @param remainingQuantity 아직 체결될 수 있는 수량. 소진된 호가가 섞여 들어올 수 있어 0도 허용한다
 */
public record RestingOrder(UUID orderId, UUID userId, long limitPrice, int remainingQuantity) {

    public RestingOrder {
        Objects.requireNonNull(orderId, "주문 ID는 null일 수 없습니다");
        Objects.requireNonNull(userId, "사용자 ID는 null일 수 없습니다");
        if (limitPrice < 1) {
            throw new IllegalArgumentException("한도가는 1 이상이어야 합니다: " + limitPrice);
        }
        if (remainingQuantity < 0) {
            throw new IllegalArgumentException("활성 잔량은 음수일 수 없습니다: " + remainingQuantity);
        }
    }

    public static RestingOrder of(Order order) {
        return new RestingOrder(order.orderId(), order.userId(), order.limitPrice(), order.remainingQuantity());
    }
}
