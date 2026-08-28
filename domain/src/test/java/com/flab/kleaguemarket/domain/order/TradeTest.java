package com.flab.kleaguemarket.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TradeTest {

    private static final UUID MAKER_ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void 공개_Fill로_투영할_수_있다() {
        Trade trade = new Trade(MAKER_ORDER_ID, 100L, 3);

        assertEquals(new Fill(100L, 3), trade.toFill());
    }

    @Test
    void maker_주문_ID가_null이면_거부한다() {
        assertThrows(NullPointerException.class, () -> new Trade(null, 100L, 3));
    }

    @Test
    void 가격과_수량의_양수_검사를_Fill에_위임한다() {
        assertThrows(IllegalArgumentException.class, () -> new Trade(MAKER_ORDER_ID, 0L, 3));
        assertThrows(IllegalArgumentException.class, () -> new Trade(MAKER_ORDER_ID, 100L, 0));
    }
}
