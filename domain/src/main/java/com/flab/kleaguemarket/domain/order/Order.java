package com.flab.kleaguemarket.domain.order;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 지정가 주문 한 건.
 *
 * <p>filledQuantity·remainingQuantity·avgFillPrice·status를 필드로 갖지 않고 fills에서 파생시킨다 —
 * 저장값으로 두면 fills와 어긋날 수 있는 상태가 생긴다 (ADR-0006의 "로그에서 파생" 원칙,
 * 설계 스펙 D4의 "언제나 원본 fills에서 재계산한다").
 *
 * @param quantity   최초 주문 수량. 체결돼도 줄지 않는다 — 줄어드는 것은 remainingQuantity다
 * @param limitPrice 한도가. 매수는 최대 지불, 매도는 최소 수취 (설계 스펙 D4)
 */
public record Order(
        UUID orderId,
        UUID userId,
        long playerId,
        Side side,
        int quantity,
        long limitPrice,
        List<Fill> fills,
        Instant createdAt
) {

    public Order {
        if (quantity < 1) {
            throw new IllegalArgumentException("주문 수량은 1주 이상이어야 합니다: " + quantity);
        }
        if (limitPrice < 1) {
            throw new IllegalArgumentException("한도가는 1 이상이어야 합니다: " + limitPrice);
        }
        fills = List.copyOf(fills);
        if (sumQuantity(fills) > quantity) {
            throw new IllegalArgumentException("체결 수량이 주문 수량을 넘을 수 없습니다: " + sumQuantity(fills) + " > " + quantity);
        }
        // 설계 스펙 D4의 불변식 — "매수 실지불 ≤ 예약액이고 매도 수취 ≥ 자기 한도가(양쪽 다 한도 위반 없음)".
        // 체결가는 maker의 한도가라 이 주문의 한도가와 다를 수 있지만, 한도를 넘는 쪽으로는 갈 수 없다
        for (Fill fill : fills) {
            boolean withinLimit = side == Side.BUY ? fill.price() <= limitPrice : fill.price() >= limitPrice;
            if (!withinLimit) {
                throw new IllegalArgumentException(
                        "체결가가 " + side + " 한도가를 위반합니다: 체결 " + fill.price() + ", 한도 " + limitPrice);
            }
        }
    }

    /** 아직 매칭을 거치지 않은 주문. orderId는 매칭 전에 발급된다 (설계 스펙 D4 — 감사·내역·fill 참조). */
    public static Order placed(UUID orderId, UUID userId, long playerId, Side side,
                               int quantity, long limitPrice, Instant createdAt) {
        return new Order(orderId, userId, playerId, side, quantity, limitPrice, List.of(), createdAt);
    }

    public Order withFills(List<Fill> matched) {
        return new Order(orderId, userId, playerId, side, quantity, limitPrice, matched, createdAt);
    }

    public int filledQuantity() {
        return sumQuantity(fills);
    }

    /**
     * 계약상 이 값은 산술 잔여가 아니라 "호가창에 살아 있어 아직 체결될 수 있는 수량"이다
     * (openapi.yaml OrderResponse.remainingQuantity). 종료된 주문은 0이므로 CANCELLED에서는
     * filledQuantity + remainingQuantity < quantity가 된다.
     *
     * <p>주문을 종료시키는 경로(취소·STP Cancel Taker)가 아직 없어 지금은 두 정의가 같은 값을 낸다.
     * 종료 경로가 들어오면 이 계산은 종료 여부를 함께 봐야 한다.
     */
    public int remainingQuantity() {
        return quantity - filledQuantity();
    }

    public OrderStatus status() {
        int filled = filledQuantity();
        if (filled == 0) {
            return OrderStatus.OPEN;
        }
        return filled < quantity ? OrderStatus.PARTIALLY_FILLED : OrderStatus.FILLED;
    }

    /**
     * 에스크로로 예약되는 "이 주문의 최대 필요 현금". 수수료 미구현인 현재는 한도가 × 잔량이다
     * (설계 스펙 D4 — 계산식이 아니라 의미가 계약이므로 정산 정책이 바뀌면 여기만 바뀐다).
     * 잔량 기준이라 신규 주문(잔량 = 주문 수량)과 미체결 잔량에 같은 식이 쓰인다.
     */
    public long maxCashRequired() {
        // 오버플로가 나면 조용히 음수가 되어 잔고 검사를 통과해 버린다 — 예외로 끊는다
        return Math.multiplyExact(limitPrice, (long) remainingQuantity());
    }

    /**
     * Σ(체결가 × 수량) ÷ Σ수량을 HALF_UP으로 정수 반올림한 표시용 값. 체결이 없으면 null.
     * 반올림이 손실적이라 정산에 쓰면 안 된다 — 금액이 필요하면 fills를 합산한다 (설계 스펙 D4).
     */
    public Long avgFillPrice() {
        int totalQuantity = filledQuantity();
        if (totalQuantity == 0) {
            return null;
        }
        long totalAmount = 0;
        for (Fill fill : fills) {
            totalAmount = Math.addExact(totalAmount, Math.multiplyExact(fill.price(), (long) fill.quantity()));
        }
        // 체결가는 양수라 (합 + 분모/2) / 분모로 HALF_UP이 나온다. 분모가 홀수면 몫이 정확히 .5일 수 없고,
        // 짝수면 분모/2가 절삭 없이 떨어지므로 두 경우 다 올바르다
        return Math.addExact(totalAmount, totalQuantity / 2) / totalQuantity;
    }

    private static int sumQuantity(List<Fill> fills) {
        int total = 0;
        for (Fill fill : fills) {
            total = Math.addExact(total, fill.quantity());
        }
        return total;
    }
}
