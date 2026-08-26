package com.flab.kleaguemarket.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 내부 체결 결과 중 Trade만의 몫을 검증한다 — maker 주문 ID가 없으면 정산이 상대를 찾을 수 없다.
 *
 * <p>가격·수량이 양수인지는 여기서 다시 보지 않는다. Trade가 {@code Fill.validate}에 위임하므로
 * FillTest가 그 규칙 한 벌을 검증한다.
 */
class TradeTest {

    private static final UUID MAKER_ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Test
    void 공개_Fill로_투영할_수_있다() {
        // maker 주문 ID는 내부에만 남고 공개 계약(openapi.yaml Fill)에는 가격·수량만 나간다
        Trade trade = new Trade(MAKER_ORDER_ID, 100L, 3);

        assertEquals(new Fill(100L, 3), trade.toFill());
    }

    @Test
    void maker_주문_ID가_null이면_거부한다() {
        assertThrows(NullPointerException.class, () -> new Trade(null, 100L, 3));
    }

    @Test
    void 가격과_수량의_양수_검사를_Fill에_위임한다() {
        // 규칙 자체는 FillTest가 검증한다. 여기서 보는 것은 Trade가 그 위임을 실제로 걸고 있는지다 —
        // 위임 한 줄이 사라져도 나머지 테스트는 전부 초록이다
        assertThrows(IllegalArgumentException.class, () -> new Trade(MAKER_ORDER_ID, 0L, 3));
        assertThrows(IllegalArgumentException.class, () -> new Trade(MAKER_ORDER_ID, 100L, 0));
    }
}
