package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V2 정산 스키마의 제약을 실제 PostgreSQL이 거부하는지 검증한다 (ADR-0009). */
@SpringBootTest
@Testcontainers
class SettlementSchemaPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24");

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private static final UUID 구매자 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID 판매자 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final AtomicLong 선수_번호 = new AtomicLong(1000);

    @Test
    void V2가_적용되어_정산_테이블이_생성된다() {
        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '2'", Map.of(), Boolean.class))
                .isTrue();
        for (String table : new String[]{
                "player_order_books", "order_states", "trades", "order_events",
                "accounts", "holdings", "cash_ledger"}) {
            assertThat(jdbc.queryForObject(
                    "SELECT to_regclass('public." + table + "') IS NOT NULL", Map.of(), Boolean.class))
                    .as(table).isTrue();
        }
    }

    // ---------- orders.priority_sequence ----------

    @Test
    void 우선순위가_NULL인_주문은_같은_선수에_여러_건_공존한다() {
        주문을_넣는다(UUID.randomUUID(), 1L, "BUY", 10, 100L, null);
        주문을_넣는다(UUID.randomUUID(), 1L, "BUY", 10, 100L, null);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE player_id = 1 AND priority_sequence IS NULL",
                Map.of(), Long.class)).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 우선순위가_양수가_아니면_검사_제약이_거부한다(long prioritySequence) {
        assertThatThrownBy(() -> 주문을_넣는다(UUID.randomUUID(), 2L, "BUY", 10, 100L, prioritySequence))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_선수에_같은_우선순위를_두_번_쓰면_유일_제약이_거부한다() {
        주문을_넣는다(UUID.randomUUID(), 3L, "BUY", 10, 100L, 1L);

        assertThatThrownBy(() -> 주문을_넣는다(UUID.randomUUID(), 3L, "SELL", 10, 100L, 1L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ---------- player_order_books ----------

    @Test
    void anchor의_다음_우선순위가_음수면_검사_제약이_거부한다() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO player_order_books (player_id, next_priority) VALUES (99, -1)", Map.of()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- order_states ----------

    @Test
    void 현재_상태의_최초_수량이_주문_헤더와_다르면_복합_외래키가_거부한다() {
        UUID orderId = UUID.randomUUID();
        주문을_넣는다(orderId, 4L, "BUY", 10, 100L, 1L);

        assertThatThrownBy(() -> 상태를_넣는다(orderId, 7, 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 없는_주문을_참조하는_현재_상태를_외래키가_거부한다() {
        assertThatThrownBy(() -> 상태를_넣는다(UUID.randomUUID(), 10, 0, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 체결_수량이_음수면_검사_제약이_거부한다() {
        UUID orderId = 상태를_붙일_주문(5L, 10);

        assertThatThrownBy(() -> 상태를_넣는다(orderId, 10, -1, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 취소_수량이_음수면_검사_제약이_거부한다() {
        UUID orderId = 상태를_붙일_주문(6L, 10);

        assertThatThrownBy(() -> 상태를_넣는다(orderId, 10, 0, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 체결과_취소의_합이_최초_수량을_넘으면_검사_제약이_거부한다() {
        UUID orderId = 상태를_붙일_주문(7L, 10);

        assertThatThrownBy(() -> 상태를_넣는다(orderId, 10, 8, 3))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 취소된_주문에_활성_잔량이_남으면_검사_제약이_거부한다() {
        UUID orderId = 상태를_붙일_주문(8L, 10);

        assertThatThrownBy(() -> 상태를_넣는다(orderId, 10, 3, 2))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- trades ----------

    @ParameterizedTest
    @ValueSource(strings = {"price", "quantity", "match_sequence"})
    void 체결의_가격_수량_매칭순서가_양수가_아니면_검사_제약이_거부한다(String column) {
        Map<String, Object> trade = 체결();
        trade.put(column, 0);

        assertThatThrownBy(() -> 체결을_넣는다(trade))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 체결의_taker_방향이_BUY도_SELL도_아니면_검사_제약이_거부한다() {
        Map<String, Object> trade = 체결();
        trade.put("taker_side", "HOLD");

        assertThatThrownBy(() -> 체결을_넣는다(trade))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void maker와_taker가_같은_주문이면_검사_제약이_거부한다() {
        Map<String, Object> trade = 체결();
        trade.put("maker_order_id", trade.get("taker_order_id"));

        assertThatThrownBy(() -> 체결을_넣는다(trade))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 같은_taker의_같은_매칭순서를_두_번_쓰면_유일_제약이_거부한다() {
        Map<String, Object> trade = 체결();
        체결을_넣는다(trade);
        Map<String, Object> 같은_순서 = new LinkedHashMap<>(trade);
        같은_순서.put("trade_id", UUID.randomUUID());

        assertThatThrownBy(() -> 체결을_넣는다(같은_순서))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 없는_주문을_참조하는_체결을_외래키가_거부한다() {
        Map<String, Object> trade = 체결();
        trade.put("maker_order_id", UUID.randomUUID());

        assertThatThrownBy(() -> 체결을_넣는다(trade))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- order_events ----------

    @Test
    void 이벤트_순번은_DB가_증가시키며_발급한다() {
        UUID orderId = 상태를_붙일_주문(14L, 10);
        이벤트를_넣는다(orderId, "ACCEPTED", 10, null);
        이벤트를_넣는다(orderId, "CANCELLED", 10, null);

        var 순번들 = jdbc.queryForList(
                "SELECT event_seq FROM order_events WHERE order_id = :id ORDER BY event_seq",
                Map.of("id", orderId), Long.class);
        assertThat(순번들).hasSize(2);
        assertThat(순번들.get(0)).isLessThan(순번들.get(1));
    }

    @Test
    void 접수_이벤트를_한_주문에_두_번_쓰면_부분_유일_인덱스가_거부한다() {
        UUID orderId = 상태를_붙일_주문(15L, 10);
        이벤트를_넣는다(orderId, "ACCEPTED", 10, null);

        assertThatThrownBy(() -> 이벤트를_넣는다(orderId, "ACCEPTED", 10, null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 취소_이벤트를_한_주문에_두_번_쓰면_부분_유일_인덱스가_거부한다() {
        UUID orderId = 상태를_붙일_주문(16L, 10);
        이벤트를_넣는다(orderId, "CANCELLED", 10, null);

        assertThatThrownBy(() -> 이벤트를_넣는다(orderId, "CANCELLED", 10, null))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 같은_주문과_같은_체결의_체결_이벤트를_두_번_쓰면_부분_유일_인덱스가_거부한다() {
        Map<String, Object> trade = 체결();
        체결을_넣는다(trade);
        UUID takerOrderId = (UUID) trade.get("taker_order_id");
        UUID tradeId = (UUID) trade.get("trade_id");
        이벤트를_넣는다(takerOrderId, "FILLED", 3, tradeId);

        assertThatThrownBy(() -> 이벤트를_넣는다(takerOrderId, "FILLED", 3, tradeId))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 체결_이벤트에_체결_ID가_없으면_검사_제약이_거부한다() {
        UUID orderId = 상태를_붙일_주문(18L, 10);

        assertThatThrownBy(() -> 이벤트를_넣는다(orderId, "FILLED", 3, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 체결이_아닌_이벤트에_체결_ID가_있으면_검사_제약이_거부한다() {
        Map<String, Object> trade = 체결();
        체결을_넣는다(trade);

        assertThatThrownBy(() -> 이벤트를_넣는다(
                (UUID) trade.get("taker_order_id"), "ACCEPTED", 10, (UUID) trade.get("trade_id")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 알_수_없는_이벤트_종류를_검사_제약이_거부한다() {
        UUID orderId = 상태를_붙일_주문(20L, 10);

        assertThatThrownBy(() -> 이벤트를_넣는다(orderId, "EXPIRED", 10, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- accounts / holdings ----------

    @Test
    void 계좌_잔액이_음수면_검사_제약이_거부한다() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO accounts (user_id, balance) VALUES (:id, -1)",
                Map.of("id", UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 보유_수량이_음수면_검사_제약이_거부한다() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO holdings (user_id, player_id, quantity) VALUES (:id, 1, -1)",
                Map.of("id", UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- cash_ledger ----------

    @Test
    void 매수_원장의_금액이_음수가_아니면_검사_제약이_거부한다() {
        UUID tradeId = 체결을_넣고_ID를_돌려준다();

        assertThatThrownBy(() -> 원장을_넣는다(tradeId, 구매자, "TRADE_BUY", 300L, 700L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 매도_원장의_금액이_양수가_아니면_검사_제약이_거부한다() {
        UUID tradeId = 체결을_넣고_ID를_돌려준다();

        assertThatThrownBy(() -> 원장을_넣는다(tradeId, 판매자, "TRADE_SELL", -300L, 700L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 반영_후_잔액이_음수면_검사_제약이_거부한다() {
        UUID tradeId = 체결을_넣고_ID를_돌려준다();

        assertThatThrownBy(() -> 원장을_넣는다(tradeId, 구매자, "TRADE_BUY", -300L, -1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 한_체결에_같은_종류의_원장을_두_번_쓰면_유일_제약이_거부한다() {
        UUID tradeId = 체결을_넣고_ID를_돌려준다();
        원장을_넣는다(tradeId, 구매자, "TRADE_BUY", -300L, 700L);

        assertThatThrownBy(() -> 원장을_넣는다(tradeId, 구매자, "TRADE_BUY", -300L, 400L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void 알_수_없는_원장_종류를_검사_제약이_거부한다() {
        UUID tradeId = 체결을_넣고_ID를_돌려준다();

        assertThatThrownBy(() -> 원장을_넣는다(tradeId, 구매자, "DIVIDEND", 300L, 700L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 없는_체결을_참조하는_원장을_외래키가_거부한다() {
        assertThatThrownBy(() -> 원장을_넣는다(UUID.randomUUID(), 구매자, "TRADE_BUY", -300L, 700L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- fixture ----------

    private void 주문을_넣는다(UUID orderId, long playerId, String side, int quantity,
                          long limitPrice, Long prioritySequence) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("order_id", orderId);
        params.put("user_id", 구매자);
        params.put("player_id", playerId);
        params.put("side", side);
        params.put("quantity", quantity);
        params.put("limit_price", limitPrice);
        params.put("created_at", OffsetDateTime.now(ZoneOffset.UTC));
        params.put("priority_sequence", prioritySequence);
        jdbc.update("""
                INSERT INTO orders (order_id, user_id, player_id, side, quantity, limit_price,
                                    created_at, priority_sequence)
                VALUES (:order_id, :user_id, :player_id, :side, :quantity, :limit_price,
                        :created_at, :priority_sequence)
                """, params);
    }

    private UUID 상태를_붙일_주문(long playerId, int quantity) {
        UUID orderId = UUID.randomUUID();
        주문을_넣는다(orderId, playerId, "BUY", quantity, 100L, 1L);
        return orderId;
    }

    private void 상태를_넣는다(UUID orderId, int quantity, int filled, int cancelled) {
        jdbc.update("""
                INSERT INTO order_states (order_id, quantity, filled_quantity, cancelled_quantity)
                VALUES (:order_id, :quantity, :filled, :cancelled)
                """, Map.of("order_id", orderId, "quantity", quantity,
                "filled", filled, "cancelled", cancelled));
    }

    /**
     * 제약을 하나씩 깨보려면 나머지 컬럼이 모두 유효한 기준 체결이 필요하다.
     * 호출마다 새 선수를 쓴다 — 재사용하면 우선순위 유일 제약에 먼저 걸린다.
     */
    private Map<String, Object> 체결() {
        long playerId = 선수_번호.incrementAndGet();
        UUID makerOrderId = UUID.randomUUID();
        UUID takerOrderId = UUID.randomUUID();
        주문을_넣는다(makerOrderId, playerId, "SELL", 3, 100L, 1L);
        주문을_넣는다(takerOrderId, playerId, "BUY", 3, 100L, 2L);
        Map<String, Object> trade = new LinkedHashMap<>();
        trade.put("trade_id", UUID.randomUUID());
        trade.put("player_id", playerId);
        trade.put("maker_order_id", makerOrderId);
        trade.put("taker_order_id", takerOrderId);
        trade.put("taker_side", "BUY");
        trade.put("price", 100L);
        trade.put("quantity", 3);
        trade.put("match_sequence", 1);
        trade.put("executed_at", OffsetDateTime.now(ZoneOffset.UTC));
        return trade;
    }

    private void 체결을_넣는다(Map<String, Object> trade) {
        jdbc.update("""
                INSERT INTO trades (trade_id, player_id, maker_order_id, taker_order_id, taker_side,
                                    price, quantity, match_sequence, executed_at)
                VALUES (:trade_id, :player_id, :maker_order_id, :taker_order_id, :taker_side,
                        :price, :quantity, :match_sequence, :executed_at)
                """, trade);
    }

    private UUID 체결을_넣고_ID를_돌려준다() {
        Map<String, Object> trade = 체결();
        체결을_넣는다(trade);
        return (UUID) trade.get("trade_id");
    }

    private void 이벤트를_넣는다(UUID orderId, String eventType, int quantity, UUID tradeId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("event_id", UUID.randomUUID());
        params.put("order_id", orderId);
        params.put("event_type", eventType);
        params.put("quantity", quantity);
        params.put("trade_id", tradeId);
        params.put("occurred_at", OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO order_events (event_id, order_id, event_type, quantity, trade_id, occurred_at)
                VALUES (:event_id, :order_id, :event_type, :quantity, :trade_id, :occurred_at)
                """, params);
    }

    private void 원장을_넣는다(UUID tradeId, UUID userId, String entryType, long amount, long balanceAfter) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("entry_id", UUID.randomUUID());
        params.put("user_id", userId);
        params.put("entry_type", entryType);
        params.put("amount", amount);
        params.put("balance_after", balanceAfter);
        params.put("trade_id", tradeId);
        params.put("occurred_at", OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO cash_ledger (entry_id, user_id, entry_type, amount, balance_after,
                                         trade_id, occurred_at)
                VALUES (:entry_id, :user_id, :entry_type, :amount, :balance_after,
                        :trade_id, :occurred_at)
                """, params);
    }
}
