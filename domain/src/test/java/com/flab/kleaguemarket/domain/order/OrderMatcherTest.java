package com.flab.kleaguemarket.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 매칭 규칙만 검증한다 — 누구와·얼마나·얼마에 체결되고, 자기 주문을 만나면 어떻게 되는가 (설계 스펙 D7).
 * 잔고·보유 이동과 영속은 매칭 바깥이라 여기서 다루지 않는다.
 *
 * <p>Spring도 DB도 mock도 쓰지 않는다. 호가창은 이미 정렬된 {@code List<Order>}로 주입되고
 * 매칭은 순수 함수라 입력을 그대로 넣고 결과를 보면 된다 (ADR-0001).
 *
 * <p>호가창의 시각은 리스트 순서와 같은 방향으로 준다. 매칭은 정렬을 신뢰하고 시각을 읽지 않지만,
 * 테스트를 읽는 사람이 "먼저 접수된 주문"을 눈으로 확인할 수 있어야 한다.
 */
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

    /** 최초 주문 수량 = 체결 수량 + 활성 잔량 + 취소 수량 (Issue #17 수량 불변조건). */
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
        // 매수 한도 102가 매도 한도 100과 만나면 100에 체결된다 — 차액은 taker가 가져간다 (설계 스펙 D4)
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

        // 가격이 같아 어느 쪽과 체결해도 금액은 같다 — 갈리는 것은 makerOrderId와 수량 배분이다
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
        // 매수 99는 매도 101을 살 수 없다 — 한도가는 교차 여부만 판정한다 (설계 스펙 D4)
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
        // Issue #17 예시 그대로 — A 매수 8@100, 호가창 [B 매도 3@100(먼저), A 매도 10@100]
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

        // 자기 주문 뒤의 C와는 체결하지 않는다 — 매칭이 거기서 끝나기 때문이다
        assertEquals(1, 결과.trades().size());
        assertEquals(B의_매도.orderId(), 결과.trades().get(0).makerOrderId());
        // 호가창의 주문들은 그대로다 (Order가 record라 애초에 불변이지만 계약으로 못박는다)
        assertEquals(List.of(), A의_매도.fills());
        assertEquals(0, A의_매도.cancelledQuantity());
        assertEquals(List.of(), C의_매도.fills());
    }

    @Test
    void 교차하지_않는_자기_주문은_취소를_유발하지_않는다() {
        // "처음으로 교차 가능한 자기 주문"이 조건이다 — 105는 매수 100과 교차하지 않으므로 그냥 매칭 종료다
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

        // 정산이 실제로 하게 될 일 — makerOrderId로 원래 주문을 찾아 그 체결분을 적용한다
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
        // 부분 체결로 호가창에 남아 있던 주문이 다시 매칭에 들어오는 경우다
        Order 매도 = 주문(사용자_B, Side.SELL, 4, 100L, 1);
        Order 과거_체결이_있는_매수 = 주문(사용자_A, Side.BUY, 10, 100L, 2)
                .withAdditionalFills(List.of(new Fill(99L, 6)));

        MatchResult 결과 = OrderMatcher.match(과거_체결이_있는_매수, List.of(매도));

        // 남은 활성 잔량 4주만 새로 체결된다 — 주문 수량 10을 다시 채우려 들면 안 된다
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

        // taker.fills()는 2건이지만 trades는 1건이다 — 원장에 과거 체결이 두 번 기록되면 안 된다
        assertEquals(List.of(new Trade(매도.orderId(), 100L, 4)), 결과.trades());
    }

    @Test
    void 활성_잔량이_0인_호가는_건너뛴다() {
        // 계약상 호가창에는 활성 주문만 들어오지만, 죽은 주문이 섞여도 수량 0짜리 체결을 만들지 않는다
        Order 이미_체결된_매도 = 주문(사용자_B, Side.SELL, 3, 100L, 1)
                .withAdditionalFills(List.of(new Fill(100L, 3)));
        Order 살아있는_매도 = 주문(사용자_C, Side.SELL, 3, 100L, 2);
        Order 매수 = 주문(사용자_A, Side.BUY, 3, 100L, 3);

        MatchResult 결과 = OrderMatcher.match(매수, List.of(이미_체결된_매도, 살아있는_매도));

        assertEquals(List.of(new Trade(살아있는_매도.orderId(), 100L, 3)), 결과.trades());
    }

    @Test
    void 자기_주문에_닿기_전에_전량_체결되면_취소가_없다() {
        // D7의 조건은 "자기 호가와 체결될 첫 순간"이다 — 잔량이 이미 0이면 자기 주문에 닿을 일이 없다.
        // 잔량 검사가 자기 주문 검사보다 뒤에 있으면 전량 체결된 주문이 CANCELLED로 뒤집힌다
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
        // 취소 사유가 이미 사라진 주문 때문에 정상 주문이 취소되면 안 된다
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
