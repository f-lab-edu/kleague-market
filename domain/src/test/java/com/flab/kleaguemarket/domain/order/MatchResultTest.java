package com.flab.kleaguemarket.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MatchResultTest {

    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MAKER_ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant CREATED_AT = Instant.parse("2026-08-26T00:00:00Z");

    private static Order taker() {
        return Order.placed(ORDER_ID, USER_ID, 42L, Side.BUY, 10, 100L, CREATED_AT);
    }

    @Test
    void 원본_리스트를_수정해도_결과의_체결_내역은_바뀌지_않는다() {
        List<Trade> mutable = new ArrayList<>(List.of(new Trade(MAKER_ORDER_ID, 100L, 3)));
        MatchResult result = new MatchResult(taker(), mutable);

        mutable.add(new Trade(MAKER_ORDER_ID, 101L, 4));
        mutable.clear();

        assertEquals(1, result.trades().size());
        assertEquals(new Trade(MAKER_ORDER_ID, 100L, 3), result.trades().get(0));
    }

    @Test
    void taker가_null이면_거부한다() {
        assertThrows(NullPointerException.class, () -> new MatchResult(null, List.of()));
    }

    @Test
    void 체결_목록이_null이면_거부한다() {
        assertThrows(NullPointerException.class, () -> new MatchResult(taker(), null));
    }
}
