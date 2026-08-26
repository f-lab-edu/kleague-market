package com.flab.kleaguemarket.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderMatcherTest {

    private static final UUID 사용자_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID 사용자_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID 사용자_C = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final long 선수 = 42L;
    private static final Instant 기준시각 = Instant.parse("2026-08-26T00:00:00Z");

    private static Order 주문(UUID userId, Side side, int quantity, long limitPrice, int 접수순서) {
        return Order.placed(UUID.randomUUID(), userId, 선수, side, quantity, limitPrice,
                기준시각.plusSeconds(접수순서));
    }

    private static void 수량_불변조건을_만족한다(Order order) {
        assertEquals(order.quantity(),
                order.filledQuantity() + order.remainingQuantity() + order.cancelledQuantity(),
                "수량 불변조건 위반: " + order);
    }

    @Test
    void 매수는_낮은_매도가부터_순서대로_체결된다() {
        Order 매도_100 = 주문(사용자_B, Side.SELL, 3, 100L, 1);
        Order 매도_101 = 주문(사용자_C, Side.SELL, 4, 101L, 2);
        Order 매수 = 주문(사용자_A, Side.BUY, 7, 102L, 3);

        MatchResult 결과 = OrderMatcher.match(매수, List.of(매도_100, 매도_101));

        assertEquals(List.of(
                new Trade(매도_100.orderId(), 100L, 3),
                new Trade(매도_101.orderId(), 101L, 4)), 결과.trades());
        assertEquals(7, 결과.taker().filledQuantity());
        assertEquals(0, 결과.taker().remainingQuantity());
        assertEquals(OrderStatus.FILLED, 결과.taker().status());
        수량_불변조건을_만족한다(결과.taker());
    }

    @Test
    void 체결가는_taker가_아니라_maker의_한도가다() {
        Order 매도 = 주문(사용자_B, Side.SELL, 3, 100L, 1);
        Order 매수 = 주문(사용자_A, Side.BUY, 3, 102L, 2);

        MatchResult 결과 = OrderMatcher.match(매수, List.of(매도));

        assertEquals(100L, 결과.trades().get(0).price());
        assertEquals(List.of(new Fill(100L, 3)), 결과.taker().fills());
    }

    @Test
    void 같은_가격이면_먼저_접수된_주문부터_체결된다() {
        Order 먼저_접수 = 주문(사용자_B, Side.SELL, 3, 100L, 1);
        Order 나중_접수 = 주문(사용자_C, Side.SELL, 3, 100L, 2);
        Order 매수 = 주문(사용자_A, Side.BUY, 4, 100L, 3);

        MatchResult 결과 = OrderMatcher.match(매수, List.of(먼저_접수, 나중_접수));

        assertEquals(List.of(
                new Trade(먼저_접수.orderId(), 100L, 3),
                new Trade(나중_접수.orderId(), 100L, 1)), 결과.trades());
        수량_불변조건을_만족한다(결과.taker());
    }

    @Test
    void 매도는_가장_높은_매수가부터_체결된다() {
        Order 매수_105 = 주문(사용자_B, Side.BUY, 5, 105L, 1);
        Order 매수_103 = 주문(사용자_C, Side.BUY, 5, 103L, 2);
        Order 매도 = 주문(사용자_A, Side.SELL, 6, 100L, 3);

        MatchResult 결과 = OrderMatcher.match(매도, List.of(매수_105, 매수_103));

        assertEquals(List.of(
                new Trade(매수_105.orderId(), 105L, 5),
                new Trade(매수_103.orderId(), 103L, 1)), 결과.trades());
        assertEquals(OrderStatus.FILLED, 결과.taker().status());
        수량_불변조건을_만족한다(결과.taker());
    }

    @Test
    void maker_수량이_부족하면_taker는_PARTIALLY_FILLED로_활성_잔량이_남는다() {
        Order 매도 = 주문(사용자_B, Side.SELL, 3, 100L, 1);
        Order 매수 = 주문(사용자_A, Side.BUY, 10, 100L, 2);

        MatchResult 결과 = OrderMatcher.match(매수, List.of(매도));

        assertEquals(3, 결과.taker().filledQuantity());
        assertEquals(7, 결과.taker().remainingQuantity());
        assertEquals(0, 결과.taker().cancelledQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, 결과.taker().status());
        수량_불변조건을_만족한다(결과.taker());
    }

    @Test
    void 가격이_교차하지_않으면_체결_없이_OPEN이다() {
        Order 매도 = 주문(사용자_B, Side.SELL, 5, 101L, 1);
        Order 매수 = 주문(사용자_A, Side.BUY, 5, 99L, 2);

        MatchResult 결과 = OrderMatcher.match(매수, List.of(매도));

        assertEquals(List.of(), 결과.trades());
        assertEquals(5, 결과.taker().remainingQuantity());
        assertEquals(OrderStatus.OPEN, 결과.taker().status());
        수량_불변조건을_만족한다(결과.taker());
    }

    @Test
    void 호가창이_비어_있으면_체결_없이_OPEN이다() {
        Order 매수 = 주문(사용자_A, Side.BUY, 5, 100L, 1);

        MatchResult 결과 = OrderMatcher.match(매수, List.of());

        assertEquals(List.of(), 결과.trades());
        assertEquals(OrderStatus.OPEN, 결과.taker().status());
        수량_불변조건을_만족한다(결과.taker());
    }

    @Test
    void 자기_주문을_만나면_선행_체결은_유지하고_잔량_전부를_취소한다() {
        Order B의_매도 = 주문(사용자_B, Side.SELL, 3, 100L, 1);
        Order A의_매도 = 주문(사용자_A, Side.SELL, 10, 100L, 2);
        Order A의_매수 = 주문(사용자_A, Side.BUY, 8, 100L, 3);

        MatchResult 결과 = OrderMatcher.match(A의_매수, List.of(B의_매도, A의_매도));

        assertEquals(List.of(new Trade(B의_매도.orderId(), 100L, 3)), 결과.trades());
        assertEquals(3, 결과.taker().filledQuantity());
        assertEquals(5, 결과.taker().cancelledQuantity());
        assertEquals(0, 결과.taker().remainingQuantity());
        assertEquals(OrderStatus.CANCELLED, 결과.taker().status());
        수량_불변조건을_만족한다(결과.taker());
    }

    @Test
    void 자기_주문과_그_뒤의_주문은_변경하지_않는다() {
        Order B의_매도 = 주문(사용자_B, Side.SELL, 3, 100L, 1);
        Order A의_매도 = 주문(사용자_A, Side.SELL, 10, 100L, 2);
        Order C의_매도 = 주문(사용자_C, Side.SELL, 10, 100L, 3);
        Order A의_매수 = 주문(사용자_A, Side.BUY, 8, 100L, 4);

        MatchResult 결과 = OrderMatcher.match(A의_매수, List.of(B의_매도, A의_매도, C의_매도));

        assertEquals(1, 결과.trades().size());
        assertEquals(B의_매도.orderId(), 결과.trades().get(0).makerOrderId());
        assertEquals(List.of(), A의_매도.fills());
        assertEquals(0, A의_매도.cancelledQuantity());
        assertEquals(List.of(), C의_매도.fills());
    }

    @Test
    void 교차하지_않는_자기_주문은_취소를_유발하지_않는다() {
        Order A의_매도 = 주문(사용자_A, Side.SELL, 5, 105L, 1);
        Order A의_매수 = 주문(사용자_A, Side.BUY, 5, 100L, 2);

        MatchResult 결과 = OrderMatcher.match(A의_매수, List.of(A의_매도));

        assertEquals(List.of(), 결과.trades());
        assertEquals(0, 결과.taker().cancelledQuantity());
        assertEquals(OrderStatus.OPEN, 결과.taker().status());
        수량_불변조건을_만족한다(결과.taker());
    }

    @Test
    void 여러_maker와_연속_체결하면_체결_내역이_덮어쓰이지_않고_누적된다() {
        Order 매도_100 = 주문(사용자_B, Side.SELL, 2, 100L, 1);
        Order 매도_101 = 주문(사용자_C, Side.SELL, 2, 101L, 2);
        Order 매도_102 = 주문(사용자_B, Side.SELL, 2, 102L, 3);
        Order 매수 = 주문(사용자_A, Side.BUY, 6, 102L, 4);

        MatchResult 결과 = OrderMatcher.match(매수, List.of(매도_100, 매도_101, 매도_102));

        assertEquals(List.of(new Fill(100L, 2), new Fill(101L, 2), new Fill(102L, 2)),
                결과.taker().fills());
        assertEquals(3, 결과.trades().size());
    }

    @Test
    void maker별_체결_후_수량과_상태를_계산할_수_있다() {
        Order 매도_100 = 주문(사용자_B, Side.SELL, 3, 100L, 1);
        Order 매도_101 = 주문(사용자_C, Side.SELL, 4, 101L, 2);
        Order 매수 = 주문(사용자_A, Side.BUY, 5, 102L, 3);

        MatchResult 결과 = OrderMatcher.match(매수, List.of(매도_100, 매도_101));

        List<Order> 호가창 = List.of(매도_100, 매도_101);
        Order 체결후_매도_100 = 체결을_적용한다(호가창, 결과, 매도_100.orderId());
        Order 체결후_매도_101 = 체결을_적용한다(호가창, 결과, 매도_101.orderId());

        assertEquals(3, 체결후_매도_100.filledQuantity());
        assertEquals(OrderStatus.FILLED, 체결후_매도_100.status());
        assertEquals(2, 체결후_매도_101.filledQuantity());
        assertEquals(2, 체결후_매도_101.remainingQuantity());
        assertEquals(OrderStatus.PARTIALLY_FILLED, 체결후_매도_101.status());
        수량_불변조건을_만족한다(체결후_매도_100);
        수량_불변조건을_만족한다(체결후_매도_101);
    }

    private static Order 체결을_적용한다(List<Order> 호가창, MatchResult 결과, UUID makerOrderId) {
        Order maker = 호가창.stream()
                .filter(o -> o.orderId().equals(makerOrderId))
                .findFirst()
                .orElseThrow();
        List<Fill> 체결분 = 결과.trades().stream()
                .filter(t -> t.makerOrderId().equals(makerOrderId))
                .map(Trade::toFill)
                .toList();
        return maker.withAdditionalFills(체결분);
    }

    @Test
    void 과거_체결이_있는_주문을_다시_매칭해도_과거_체결이_유지된다() {
        Order 매도 = 주문(사용자_B, Side.SELL, 4, 100L, 1);
        Order 과거_체결이_있는_매수 = 주문(사용자_A, Side.BUY, 10, 100L, 2)
                .withAdditionalFills(List.of(new Fill(99L, 6)));

        MatchResult 결과 = OrderMatcher.match(과거_체결이_있는_매수, List.of(매도));

        assertEquals(List.of(new Fill(99L, 6), new Fill(100L, 4)), 결과.taker().fills());
        assertEquals(10, 결과.taker().filledQuantity());
        assertEquals(OrderStatus.FILLED, 결과.taker().status());
        수량_불변조건을_만족한다(결과.taker());
    }

    @Test
    void 결과의_체결_목록에는_이번_호출에서_새로_생긴_체결만_담긴다() {
        Order 매도 = 주문(사용자_B, Side.SELL, 4, 100L, 1);
        Order 과거_체결이_있는_매수 = 주문(사용자_A, Side.BUY, 10, 100L, 2)
                .withAdditionalFills(List.of(new Fill(99L, 6)));

        MatchResult 결과 = OrderMatcher.match(과거_체결이_있는_매수, List.of(매도));

        assertEquals(List.of(new Trade(매도.orderId(), 100L, 4)), 결과.trades());
    }

    @Test
    void 활성_잔량이_0인_호가는_건너뛴다() {
        Order 이미_체결된_매도 = 주문(사용자_B, Side.SELL, 3, 100L, 1)
                .withAdditionalFills(List.of(new Fill(100L, 3)));
        Order 살아있는_매도 = 주문(사용자_C, Side.SELL, 3, 100L, 2);
        Order 매수 = 주문(사용자_A, Side.BUY, 3, 100L, 3);

        MatchResult 결과 = OrderMatcher.match(매수, List.of(이미_체결된_매도, 살아있는_매도));

        assertEquals(List.of(new Trade(살아있는_매도.orderId(), 100L, 3)), 결과.trades());
    }

    @Test
    void 자기_주문에_닿기_전에_전량_체결되면_취소가_없다() {
        Order B의_매도 = 주문(사용자_B, Side.SELL, 3, 100L, 1);
        Order A의_매도 = 주문(사용자_A, Side.SELL, 5, 100L, 2);
        Order A의_매수 = 주문(사용자_A, Side.BUY, 3, 100L, 3);

        MatchResult 결과 = OrderMatcher.match(A의_매수, List.of(B의_매도, A의_매도));

        assertEquals(List.of(new Trade(B의_매도.orderId(), 100L, 3)), 결과.trades());
        assertEquals(0, 결과.taker().cancelledQuantity());
        assertEquals(OrderStatus.FILLED, 결과.taker().status());
        수량_불변조건을_만족한다(결과.taker());
    }

    @Test
    void 이미_전량_체결된_자기_주문은_취소를_유발하지_않는다() {
        Order A의_죽은_매도 = 주문(사용자_A, Side.SELL, 3, 100L, 1)
                .withAdditionalFills(List.of(new Fill(100L, 3)));
        Order B의_매도 = 주문(사용자_B, Side.SELL, 3, 100L, 2);
        Order A의_매수 = 주문(사용자_A, Side.BUY, 3, 100L, 3);

        MatchResult 결과 = OrderMatcher.match(A의_매수, List.of(A의_죽은_매도, B의_매도));

        assertEquals(0, 결과.taker().cancelledQuantity());
        assertEquals(OrderStatus.FILLED, 결과.taker().status());
    }

    @Test
    void 같은_입력을_반복하면_체결_순서와_결과가_동일하다() {
        List<Order> 호가창 = List.of(
                주문(사용자_B, Side.SELL, 3, 100L, 1),
                주문(사용자_C, Side.SELL, 4, 101L, 2));
        Order 매수 = 주문(사용자_A, Side.BUY, 6, 102L, 3);

        MatchResult 첫번째 = OrderMatcher.match(매수, 호가창);
        MatchResult 두번째 = OrderMatcher.match(매수, 호가창);

        assertEquals(첫번째, 두번째);
        assertEquals(첫번째.trades(), 두번째.trades());
    }

}
