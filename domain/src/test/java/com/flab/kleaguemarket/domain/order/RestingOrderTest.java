package com.flab.kleaguemarket.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RestingOrderTest {

    private static final UUID 주문_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID 사용자_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void 주문에서_투영하면_최초_수량이_아니라_활성_잔량을_가져온다() {
        Order 절반_체결된_매도 = new Order(주문_ID, 사용자_ID, 42L, Side.SELL, 10, 100L,
                List.of(new Fill(100L, 4)), Instant.parse("2026-08-26T00:00:00Z"));

        RestingOrder resting = RestingOrder.of(절반_체결된_매도);

        assertEquals(6, resting.remainingQuantity());
        assertEquals(주문_ID, resting.orderId());
        assertEquals(사용자_ID, resting.userId());
        assertEquals(100L, resting.limitPrice());
    }

    @Test
    void 활성_잔량이_0인_호가도_만들_수_있다() {
        assertEquals(0, new RestingOrder(주문_ID, 사용자_ID, 100L, 0).remainingQuantity());
    }

    @Test
    void 주문_ID나_사용자_ID가_null이면_거부한다() {
        assertThrows(NullPointerException.class, () -> new RestingOrder(null, 사용자_ID, 100L, 3));
        assertThrows(NullPointerException.class, () -> new RestingOrder(주문_ID, null, 100L, 3));
    }

    @Test
    void 한도가가_양수가_아니거나_잔량이_음수면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new RestingOrder(주문_ID, 사용자_ID, 0L, 3));
        assertThrows(IllegalArgumentException.class, () -> new RestingOrder(주문_ID, 사용자_ID, 100L, -1));
    }
}
