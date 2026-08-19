package com.flab.kleaguemarket.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Order의 파생 계산과 생성자 불변식만 검증한다. 유스케이스 조율(포트 호출·거부 판정)은
 * app의 PlaceOrderServiceTest가 맡는다 — 같은 규칙을 두 계층에서 중복 검증하지 않는다.
 *
 * <p>Spring도 mock도 쓰지 않는다. Order는 fills에서 상태를 파생시키는 순수 record라
 * 입력을 그대로 넣고 결과를 보면 된다 (ADR-0001의 "domain은 순수 자바").
 */
class OrderTest {

    private static final UUID ORDER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final long PLAYER_ID = 42L;
    private static final Instant CREATED_AT = Instant.parse("2026-08-19T00:00:00Z");

    @Test
    void 체결이_없으면_OPEN이고_잔량은_주문_수량_그대로다() {
        Order order = new Order(ORDER_ID, USER_ID, PLAYER_ID, Side.BUY, 10, 100L, List.of(), CREATED_AT);

        assertEquals(0, order.filledQuantity());
        assertEquals(10, order.remainingQuantity());
        assertEquals(OrderStatus.OPEN, order.status());
        assertNull(order.avgFillPrice());
    }

    @Test
    void 일부만_체결되면_PARTIALLY_FILLED이고_남은_수량이_잔량이_된다() {
        Order order = new Order(ORDER_ID, USER_ID, PLAYER_ID, Side.BUY, 10, 102L,
                List.of(new Fill(100L, 3)), CREATED_AT);

        assertEquals(3, order.filledQuantity());
        assertEquals(7, order.remainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, order.status());
    }

    @Test
    void 전량_체결되면_FILLED이고_잔량은_0이_된다() {
        // 매수 7주가 여러 가격대와 교차하면 fill마다 체결가가 다르다 (설계 스펙 D4)
        Order order = new Order(ORDER_ID, USER_ID, PLAYER_ID, Side.BUY, 7, 102L,
                List.of(new Fill(100L, 3), new Fill(101L, 4)), CREATED_AT);

        assertEquals(7, order.filledQuantity());
        assertEquals(0, order.remainingQuantity());
        assertEquals(OrderStatus.FILLED, order.status());
    }

    @Test
    void 평균_체결가는_체결_수량으로_가중한다() {
        // D4의 반례 — 평균의 평균은 101이지만 올바른 값은 701/7 = 100.14… → 100이다
        Order order = new Order(ORDER_ID, USER_ID, PLAYER_ID, Side.BUY, 7, 102L,
                List.of(new Fill(100L, 6), new Fill(101L, 1)), CREATED_AT);

        assertEquals(100L, order.avgFillPrice());
    }

    @Test
    void 평균_체결가가_정확히_반값이면_올림한다() {
        // 201/2 = 100.5 — HALF_UP이면 101, HALF_EVEN이면 100이라 두 정책이 갈리는 지점이다 (D4는 HALF_UP)
        Order order = new Order(ORDER_ID, USER_ID, PLAYER_ID, Side.BUY, 2, 102L,
                List.of(new Fill(100L, 1), new Fill(101L, 1)), CREATED_AT);

        assertEquals(101L, order.avgFillPrice());
    }

    @Test
    void 매수의_최대_필요_현금은_한도가_곱하기_잔량이다() {
        // 이미 체결된 4주는 예약할 이유가 없다 — 주문 수량이 아니라 잔량이 기준이다 (설계 스펙 D4)
        Order order = new Order(ORDER_ID, USER_ID, PLAYER_ID, Side.BUY, 10, 100L,
                List.of(new Fill(90L, 4)), CREATED_AT);

        assertEquals(600L, order.maxCashRequired());
    }

    @Test
    void 체결_수량이_주문_수량을_넘으면_생성에_실패한다() {
        assertThrows(IllegalArgumentException.class, () -> new Order(
                ORDER_ID, USER_ID, PLAYER_ID, Side.BUY, 5, 100L,
                List.of(new Fill(100L, 3), new Fill(100L, 3)), CREATED_AT));
    }

    @Test
    void 매수는_한도가보다_비싼_체결을_거부한다() {
        // 매수 한도 102에 103 체결은 "매수 실지불 ≤ 예약액" 불변식 위반이다 (설계 스펙 D4)
        assertThrows(IllegalArgumentException.class, () -> new Order(
                ORDER_ID, USER_ID, PLAYER_ID, Side.BUY, 10, 102L,
                List.of(new Fill(103L, 1)), CREATED_AT));
    }

    @Test
    void 매도는_한도가보다_싼_체결을_거부한다() {
        // 매도는 부등호가 반대다 — "매도 수취 ≥ 자기 한도가" (설계 스펙 D4)
        assertThrows(IllegalArgumentException.class, () -> new Order(
                ORDER_ID, USER_ID, PLAYER_ID, Side.SELL, 10, 102L,
                List.of(new Fill(101L, 1)), CREATED_AT));
    }

    @Test
    void 매수는_한도가와_같은_가격의_체결을_허용한다() {
        // 계약은 "체결가 ≤ 한도가"다 — 등호가 빠지면 정확히 한도가에 체결된 정상 주문이 거부된다 (설계 스펙 D4)
        Order order = new Order(ORDER_ID, USER_ID, PLAYER_ID, Side.BUY, 10, 100L,
                List.of(new Fill(100L, 3)), CREATED_AT);

        assertEquals(3, order.filledQuantity());
    }

    @Test
    void 매도는_한도가와_같은_가격의_체결을_허용한다() {
        // 매도는 "체결가 ≥ 한도가"로 부등호가 뒤집힐 뿐 등호는 똑같이 포함된다 (설계 스펙 D4)
        Order order = new Order(ORDER_ID, USER_ID, PLAYER_ID, Side.SELL, 10, 100L,
                List.of(new Fill(100L, 3)), CREATED_AT);

        assertEquals(3, order.filledQuantity());
    }
}
