package com.flab.kleaguemarket.domain.order;

/**
 * 체결 한 건. price는 maker의 한도가이지 이 주문의 한도가가 아니다 (설계 스펙 D4).
 *
 * <p>가격·수량은 언제나 양수다. 매칭이 지키는 규칙이지만 값 자체에 못박아 두면 매칭 바깥에서
 * 만들어진 체결도 같은 규칙을 통과한다 — 0주 체결은 원장에 의미 없이 건수만 늘리고,
 * 0원 체결은 정산 금액을 조용히 0으로 만든다.
 */
public record Fill(long price, int quantity) {

    public Fill {
        validate(price, quantity);
    }

    /** {@link Trade}도 같은 규칙을 쓴다. 두 벌로 두면 한쪽만 고쳐진다. */
    static void validate(long price, int quantity) {
        if (price < 1) {
            throw new IllegalArgumentException("체결가는 1 이상이어야 합니다: " + price);
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("체결 수량은 1주 이상이어야 합니다: " + quantity);
        }
    }
}
