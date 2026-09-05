package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flab.kleaguemarket.domain.order.OrderRejectedException;
import com.flab.kleaguemarket.domain.order.OrderStatus;
import com.flab.kleaguemarket.domain.order.Side;
import com.flab.kleaguemarket.domain.order.port.TraderAccount;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 한 번의 매칭 결과가 주문·체결·자산·원장에 전부 반영되는지 실제 PostgreSQL에서 검증한다
 * (ADR-0005 단일 트랜잭션, 설계 스펙 D4).
 */
@SpringBootTest
@Import(TestOrderConfig.class)
@Testcontainers
class OrderSettlementPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24");

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PlaceOrderService service;

    @Autowired
    TraderAccount traderAccount;

    private static final AtomicLong 선수_번호 = new AtomicLong();

    @Test
    void 미체결_주문은_헤더와_접수_이벤트와_전체_잔량_상태만_남긴다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(10_000L);

        var 주문 = service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 10, 100L));

        assertThat(주문.status()).isEqualTo(OrderStatus.OPEN);
        assertThat(수를_센다("SELECT count(*) FROM orders WHERE order_id = :id", 주문.orderId())).isEqualTo(1);
        assertThat(수를_센다("""
                SELECT count(*) FROM order_events WHERE order_id = :id AND event_type = 'ACCEPTED'
                """, 주문.orderId())).isEqualTo(1);
        assertThat(상태(주문.orderId())).containsExactly(Map.entry("filled", 0), Map.entry("cancelled", 0));
        assertThat(수를_센다("SELECT count(*) FROM trades WHERE taker_order_id = :id", 주문.orderId())).isZero();
        assertThat(잔액(매수자)).isEqualTo(10_000L);
        assertThat(원장_건수(매수자)).isZero();
    }

    @Test
    void 미체결_매수_주문의_예약은_한도가_곱하기_잔량으로_유도된다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(10_000L);

        service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 10, 100L));

        // 예약은 컬럼이 아니라 활성 주문에서 유도한다 (openapi.yaml MeResponse.reservedBalance)
        assertThat(traderAccount.snapshot(매수자, 선수).availableBalance()).isEqualTo(9_000L);
    }

    @Test
    void 매도_주문의_예약_수량은_미체결과_부분_체결_모두_잔량에서_유도된다() {
        long 선수 = 선수를_새로_연다();
        UUID 매도자 = 계좌를_연다(0L);
        UUID 매수자 = 계좌를_연다(10_000L);
        보유를_넣는다(매도자, 선수, 10);

        service.place(new PlaceOrderCommand(매도자, 선수, Side.SELL, 4, 100L));

        TraderAccount.Snapshot 미체결 = traderAccount.snapshot(매도자, 선수);
        assertThat(미체결.heldQuantity()).isEqualTo(10);
        assertThat(미체결.availableQuantity()).isEqualTo(6);

        // 1주만 가져가게 해 잔량을 3으로 만든다. 예약이 최초 수량이 아니라 잔량을 따라가야 한다
        service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 1, 100L));

        TraderAccount.Snapshot 부분_체결 = traderAccount.snapshot(매도자, 선수);
        assertThat(부분_체결.heldQuantity()).isEqualTo(9);
        assertThat(부분_체결.availableQuantity()).isEqualTo(6);
    }

    @Test
    void 여러_maker와_체결하면_체결가별로_정산한다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(10_000L);
        UUID 판매자_100 = 계좌를_연다(0L);
        UUID 판매자_101 = 계좌를_연다(0L);
        보유를_넣는다(판매자_100, 선수, 3);
        보유를_넣는다(판매자_101, 선수, 4);
        var 매도_100 = service.place(new PlaceOrderCommand(판매자_100, 선수, Side.SELL, 3, 100L));
        var 매도_101 = service.place(new PlaceOrderCommand(판매자_101, 선수, Side.SELL, 4, 101L));

        var 매수 = service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 7, 102L));

        assertThat(매수.status()).isEqualTo(OrderStatus.FILLED);
        // 3×100 + 4×101 = 704. avgFillPrice(101)×7 = 707도, 한도가 102×7 = 714도 아니다
        assertThat(잔액(매수자)).isEqualTo(10_000L - 704L);
        assertThat(잔액(판매자_100)).isEqualTo(300L);
        assertThat(잔액(판매자_101)).isEqualTo(404L);
        assertThat(보유(매수자, 선수)).isEqualTo(7);
        assertThat(보유(판매자_100, 선수)).isZero();
        assertThat(보유(판매자_101, 선수)).isZero();
        assertThat(수를_센다("SELECT count(*) FROM trades WHERE taker_order_id = :id", 매수.orderId()))
                .isEqualTo(2);
        assertThat(원장_건수(매수자) + 원장_건수(판매자_100) + 원장_건수(판매자_101)).isEqualTo(4);
        assertThat(상태(매도_100.orderId())).containsExactly(Map.entry("filled", 3), Map.entry("cancelled", 0));
        assertThat(상태(매도_101.orderId())).containsExactly(Map.entry("filled", 4), Map.entry("cancelled", 0));
    }

    @Test
    void 구매자_원장의_반영_후_잔액이_체결_순서대로_이어진다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(10_000L);
        UUID 판매자_100 = 계좌를_연다(0L);
        UUID 판매자_101 = 계좌를_연다(0L);
        보유를_넣는다(판매자_100, 선수, 3);
        보유를_넣는다(판매자_101, 선수, 4);
        service.place(new PlaceOrderCommand(판매자_100, 선수, Side.SELL, 3, 100L));
        service.place(new PlaceOrderCommand(판매자_101, 선수, Side.SELL, 4, 101L));

        service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 7, 102L));

        List<Map<String, Object>> 원장 = jdbc.queryForList("""
                SELECT l.amount, l.balance_after
                  FROM cash_ledger l JOIN trades t ON t.trade_id = l.trade_id
                 WHERE l.user_id = :id ORDER BY t.match_sequence
                """, Map.of("id", 매수자));
        assertThat(원장).extracting(r -> r.get("amount")).containsExactly(-300L, -404L);
        assertThat(원장).extracting(r -> r.get("balance_after")).containsExactly(9_700L, 9_296L);
    }

    @Test
    void 부분_체결은_체결분만_반영하고_잔량만_활성으로_남긴다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(10_000L);
        UUID 판매자 = 계좌를_연다(0L);
        보유를_넣는다(판매자, 선수, 3);
        service.place(new PlaceOrderCommand(판매자, 선수, Side.SELL, 3, 100L));

        var 매수 = service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 10, 100L));

        assertThat(매수.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(매수.remainingQuantity()).isEqualTo(7);
        assertThat(잔액(매수자)).isEqualTo(10_000L - 300L);
        assertThat(보유(매수자, 선수)).isEqualTo(3);
        assertThat(상태(매수.orderId())).containsExactly(Map.entry("filled", 3), Map.entry("cancelled", 0));
        // 체결분은 예약에서 빠지고 잔량 7×100만 남는다
        assertThat(traderAccount.snapshot(매수자, 선수).availableBalance()).isEqualTo(9_000L);
    }

    @Test
    void 매도_taker도_구매자와_판매자를_올바르게_식별한다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(10_000L);
        UUID 매도자 = 계좌를_연다(0L);
        보유를_넣는다(매도자, 선수, 5);
        service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 5, 100L));

        var 매도 = service.place(new PlaceOrderCommand(매도자, 선수, Side.SELL, 5, 100L));

        assertThat(매도.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(잔액(매도자)).isEqualTo(500L);
        assertThat(잔액(매수자)).isEqualTo(9_500L);
        assertThat(보유(매수자, 선수)).isEqualTo(5);
        assertThat(보유(매도자, 선수)).isZero();
        assertThat(원장_종류(매수자)).containsExactly("TRADE_BUY");
        assertThat(원장_종류(매도자)).containsExactly("TRADE_SELL");
    }

    @Test
    void 자기_주문을_만나면_선행_체결만_정산하고_잔량을_취소한다() {
        long 선수 = 선수를_새로_연다();
        UUID A = 계좌를_연다(10_000L);
        UUID B = 계좌를_연다(0L);
        보유를_넣는다(B, 선수, 3);
        보유를_넣는다(A, 선수, 10);
        service.place(new PlaceOrderCommand(B, 선수, Side.SELL, 3, 100L));
        var A의_매도 = service.place(new PlaceOrderCommand(A, 선수, Side.SELL, 10, 100L));

        var A의_매수 = service.place(new PlaceOrderCommand(A, 선수, Side.BUY, 8, 100L));

        assertThat(A의_매수.status()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(A의_매수.filledQuantity()).isEqualTo(3);
        assertThat(A의_매수.cancelledQuantity()).isEqualTo(5);
        assertThat(수를_센다("SELECT count(*) FROM trades WHERE taker_order_id = :id", A의_매수.orderId()))
                .isEqualTo(1);
        assertThat(수를_센다("""
                SELECT count(*) FROM order_events WHERE order_id = :id AND event_type = 'CANCELLED'
                """, A의_매수.orderId())).isEqualTo(1);
        // 자기 자신과의 체결·원장은 만들어지지 않고 기존 자기 매도는 그대로다
        assertThat(수를_센다("""
                SELECT count(*) FROM trades WHERE maker_order_id = :id
                """, A의_매도.orderId())).isZero();
        assertThat(상태(A의_매도.orderId())).containsExactly(Map.entry("filled", 0), Map.entry("cancelled", 0));
        // 취소된 5주는 활성 주문에서 빠져 예약에도 남지 않는다 (300 체결 + A의 매도 예약 없음)
        assertThat(traderAccount.snapshot(A, 선수).availableBalance()).isEqualTo(9_700L);
    }

    @Test
    void 거래가_정지된_선수는_anchor를_만들기_전에_거부한다() {
        long 선수 = TestOrderConfig.거래_정지_선수;
        UUID 매수자 = 계좌를_연다(10_000L);

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 10, 100L)))
                .isInstanceOf(OrderRejectedException.class);
        assertThat(수를_센다("SELECT count(*) FROM player_order_books WHERE player_id = :id", 선수)).isZero();
    }

    @Test
    void 가용_잔고가_부족하면_주문을_남기지_않는다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(500L);

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 10, 100L)))
                .isInstanceOf(OrderRejectedException.class);
        assertThat(수를_센다("SELECT count(*) FROM orders WHERE player_id = :id", 선수)).isZero();
    }

    // ---------- fixture ----------

    private long 선수를_새로_연다() {
        return 선수_번호.incrementAndGet();
    }

    private UUID 계좌를_연다(long balance) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO accounts (user_id, balance) VALUES (:id, :balance)",
                Map.of("id", userId, "balance", balance));
        return userId;
    }

    private void 보유를_넣는다(UUID userId, long playerId, int quantity) {
        jdbc.update("""
                INSERT INTO holdings (user_id, player_id, quantity) VALUES (:id, :player, :quantity)
                """, Map.of("id", userId, "player", playerId, "quantity", quantity));
    }

    private long 잔액(UUID userId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE user_id = :id",
                Map.of("id", userId), Long.class);
    }

    private int 보유(UUID userId, long playerId) {
        return jdbc.queryForObject("""
                SELECT coalesce(max(quantity), 0) FROM holdings WHERE user_id = :id AND player_id = :player
                """, Map.of("id", userId, "player", playerId), Integer.class);
    }

    private long 수를_센다(String sql, Object id) {
        return jdbc.queryForObject(sql, Map.of("id", id), Long.class);
    }

    private long 원장_건수(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM cash_ledger WHERE user_id = :id",
                Map.of("id", userId), Long.class);
    }

    private List<String> 원장_종류(UUID userId) {
        return jdbc.queryForList("SELECT entry_type FROM cash_ledger WHERE user_id = :id",
                Map.of("id", userId), String.class);
    }

    private List<Map.Entry<String, Integer>> 상태(UUID orderId) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT filled_quantity, cancelled_quantity FROM order_states WHERE order_id = :id
                """, Map.of("id", orderId));
        return List.of(Map.entry("filled", (Integer) row.get("filled_quantity")),
                Map.entry("cancelled", (Integer) row.get("cancelled_quantity")));
    }
}
