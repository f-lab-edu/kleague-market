package com.flab.kleaguemarket.domain.order;

/** 체결 한 건. 가격은 maker 주문의 한도가이며, 가격과 수량은 양수다. */
public record Fill(long price, int quantity) {

    public Fill {
        validate(price, quantity);
    }

    static void validate(long price, int quantity) {
        if (price < 1) {
            throw new IllegalArgumentException("체결가는 1 이상이어야 합니다: " + price);
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("체결 수량은 1주 이상이어야 합니다: " + quantity);
        }
    }
}
