package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flab.kleaguemarket.domain.order.Side;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 내부 대사가 정상 정산을 통과시키고 고의로 어긋낸 자료를 검출하는지 검증한다. */
@SpringBootTest
@Import(TestOrderConfig.class)
@Testcontainers
class SettlementReconciliationPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24");

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PlaceOrderService service;

    private static final AtomicLong 선수_번호 = new AtomicLong(3000);

    /**
     * 대사는 DB 전체를 본다. 앞 테스트가 고의로 어긋낸 자료가 남아 있으면 "정상이면 불일치 0건"을
     * 확인할 수 없으므로 매번 빈 상태에서 시작한다.
     */
    @BeforeEach
    void 빈_상태로_시작한다() {
        jdbc.getJdbcTemplate().execute("""
                TRUNCATE cash_ledger, order_events, trades, order_states, orders,
                         accounts, holdings, player_order_books
                """);
    }

    @Test
    void 정상_체결_뒤에는_불일치가_없다() {
        체결을_만든다();

        assertThat(대사()).isEmpty();
    }

    @Test
    void 원장_한_건을_지우면_검출한다() {
        UUID tradeId = 체결을_만든다();

        jdbc.update("DELETE FROM cash_ledger WHERE trade_id = :id AND entry_type = 'TRADE_SELL'",
                Map.of("id", tradeId));

        assertThat(대사()).extracting(SettlementReconciliation.Mismatch::check)
                .contains("체결당 매수·매도 원장 각 한 건과 델타 합 0");
    }

    @Test
    void 원장_금액을_조작하면_검출한다() {
        UUID tradeId = 체결을_만든다();

        jdbc.update("UPDATE cash_ledger SET amount = -1 WHERE trade_id = :id AND entry_type = 'TRADE_BUY'",
                Map.of("id", tradeId));

        assertThat(대사()).extracting(SettlementReconciliation.Mismatch::check)
                .contains("원장 금액이 체결 가격 곱하기 수량", "체결당 매수·매도 원장 각 한 건과 델타 합 0");
    }

    @Test
    void 원장의_사용자를_바꾸면_검출한다() {
        UUID tradeId = 체결을_만든다();

        jdbc.update("""
                UPDATE cash_ledger SET user_id = :엉뚱한_사용자
                 WHERE trade_id = :id AND entry_type = 'TRADE_BUY'
                """, Map.of("id", tradeId, "엉뚱한_사용자", 계좌를_연다(0L)));

        assertThat(대사()).extracting(SettlementReconciliation.Mismatch::check)
                .contains("원장의 사용자가 실제 구매자와 판매자");
    }

    @Test
    void 체결_이벤트를_지우면_검출한다() {
        UUID tradeId = 체결을_만든다();

        jdbc.update("DELETE FROM order_events WHERE trade_id = :id", Map.of("id", tradeId));

        assertThat(대사()).extracting(SettlementReconciliation.Mismatch::check)
                .contains("체결마다 maker와 taker의 체결 이벤트가 정확히 하나씩");
    }

    @Test
    void 행_제약을_통과하도록_체결과_취소_수량을_함께_바꿔도_검출한다() {
        UUID tradeId = 체결을_만든다();
        UUID makerOrderId = jdbc.queryForObject(
                "SELECT maker_order_id FROM trades WHERE trade_id = :id", Map.of("id", tradeId), UUID.class);

        // filled만 바꾸면 행 CHECK가 먼저 거부한다. 함께 옮겨야 행 안에서는 앞뒤가 맞으면서
        // 체결 원본과는 어긋난 상태가 되어 대사만이 잡아낼 수 있다
        jdbc.update("""
                UPDATE order_states SET filled_quantity = filled_quantity - 1, cancelled_quantity = 1
                 WHERE order_id = :id
                """, Map.of("id", makerOrderId));

        assertThat(대사()).extracting(SettlementReconciliation.Mismatch::check)
                .contains("신규 주문마다 현재 상태가 있고 체결 수량이 체결 원본과 일치");
    }

    @Test
    void 같은_사용자의_두_주문이_체결되면_검출한다() {
        자기_자신과의_체결을_손으로_넣는다();

        // 매칭기의 STP가 막고 있어 정상 경로로는 만들어질 수 없다. 그 방어선이 뚫렸을 때
        // 원장 합은 0이고 사용자도 제자리라 다른 검사는 모두 통과한다 (설계 스펙 D7)
        assertThat(대사()).extracting(SettlementReconciliation.Mismatch::check)
                .containsExactly("체결 양쪽이 서로 다른 사용자");
    }

    @Test
    void 제삼의_주문에_체결_이벤트가_붙으면_검출한다() {
        UUID tradeId = 체결을_만든다();
        UUID 무관한_주문 = 주문_헤더를_손으로_넣는다(선수를_새로_연다(), 계좌를_연다(0L), "BUY", 3, 100L, 1L);
        jdbc.update("INSERT INTO order_states (order_id, quantity) VALUES (:id, 3)",
                Map.of("id", 무관한_주문));

        이벤트를_손으로_넣는다(무관한_주문, 3, tradeId);

        assertThat(대사()).extracting(SettlementReconciliation.Mismatch::check)
                .contains("체결마다 maker와 taker의 체결 이벤트가 정확히 하나씩");
    }

    @Test
    void 신규_주문의_현재_상태가_사라지면_검출한다() {
        UUID tradeId = 체결을_만든다();
        UUID takerOrderId = jdbc.queryForObject(
                "SELECT taker_order_id FROM trades WHERE trade_id = :id", Map.of("id", tradeId), UUID.class);

        // 상태를 참조하는 외래 키가 없어 행만 사라질 수 있다. 그러면 그 주문은 호가창과 예약에서
        // 조용히 빠지는데, 상태 테이블을 훑는 방식으로는 사라진 주문을 볼 수 없다
        jdbc.update("DELETE FROM order_states WHERE order_id = :id", Map.of("id", takerOrderId));

        assertThat(대사()).extracting(SettlementReconciliation.Mismatch::check)
                .contains("신규 주문마다 현재 상태가 있고 체결 수량이 체결 원본과 일치");
    }

    @Test
    void 레거시_주문에_상태가_붙으면_검출한다() {
        UUID 레거시 = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (order_id, user_id, player_id, side, quantity, limit_price, created_at)
                VALUES (:id, :user, :player, 'SELL', 5, 100, now())
                """, Map.of("id", 레거시, "user", UUID.randomUUID(), "player", 선수를_새로_연다()));
        jdbc.update("INSERT INTO order_states (order_id, quantity) VALUES (:id, 5)", Map.of("id", 레거시));

        assertThat(대사()).extracting(SettlementReconciliation.Mismatch::check)
                .contains("레거시 주문에 상태·체결·이벤트가 없음");
    }

    @Test
    void 대사는_어떤_행도_바꾸지_않는다() {
        체결을_만든다();
        Map<String, Object> 이전 = 전체_지문();

        대사();

        assertThat(전체_지문()).isEqualTo(이전);
    }

    // ---------- fixture ----------

    private List<SettlementReconciliation.Mismatch> 대사() {
        return new SettlementReconciliation(jdbc).findMismatches();
    }

    /** 매도 3주 @100을 매수 3주가 전량 가져가는 정상 체결 하나를 만든다. */
    private UUID 체결을_만든다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(10_000L);
        UUID 판매자 = 계좌를_연다(0L);
        보유를_넣는다(판매자, 선수, 3);
        service.place(new PlaceOrderCommand(판매자, 선수, Side.SELL, 3, 100L));
        var 매수 = service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 3, 100L));
        return jdbc.queryForObject("SELECT trade_id FROM trades WHERE taker_order_id = :id",
                Map.of("id", 매수.orderId()), UUID.class);
    }

    /**
     * 자기 자신과 체결한 기록을 직접 넣는다. 서비스로는 만들 수 없으므로 손으로 쓴다.
     * 자전거래라는 사실 하나만 빼면 원장·이벤트·상태가 모두 앞뒤가 맞는 형태로 만든다.
     */
    private void 자기_자신과의_체결을_손으로_넣는다() {
        long 선수 = 선수를_새로_연다();
        UUID 한_사람 = 계좌를_연다(10_000L);
        UUID maker = 주문_헤더를_손으로_넣는다(선수, 한_사람, "SELL", 3, 100L, 1L);
        UUID taker = 주문_헤더를_손으로_넣는다(선수, 한_사람, "BUY", 3, 100L, 2L);
        보유를_넣는다(한_사람, 선수, 3);
        jdbc.update("""
                INSERT INTO order_states (order_id, quantity, filled_quantity)
                VALUES (:maker, 3, 3), (:taker, 3, 3)
                """, Map.of("maker", maker, "taker", taker));

        UUID tradeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO trades (trade_id, player_id, maker_order_id, taker_order_id, taker_side,
                                    price, quantity, match_sequence, executed_at)
                VALUES (:trade, :player, :maker, :taker, 'BUY', 100, 3, 1, now())
                """, Map.of("trade", tradeId, "player", 선수, "maker", maker, "taker", taker));
        원장을_손으로_넣는다(tradeId, 한_사람, "TRADE_BUY", -300L, 9_700L);
        원장을_손으로_넣는다(tradeId, 한_사람, "TRADE_SELL", 300L, 10_000L);
        이벤트를_손으로_넣는다(maker, 3, tradeId);
        이벤트를_손으로_넣는다(taker, 3, tradeId);
    }

    private UUID 주문_헤더를_손으로_넣는다(long playerId, UUID userId, String side, int quantity,
                                 long limitPrice, Long prioritySequence) {
        UUID orderId = UUID.randomUUID();
        Map<String, Object> params = new HashMap<>();
        params.put("order_id", orderId);
        params.put("user_id", userId);
        params.put("player_id", playerId);
        params.put("side", side);
        params.put("quantity", quantity);
        params.put("limit_price", limitPrice);
        params.put("priority_sequence", prioritySequence);
        jdbc.update("""
                INSERT INTO orders (order_id, user_id, player_id, side, quantity, limit_price,
                                    created_at, priority_sequence)
                VALUES (:order_id, :user_id, :player_id, :side, :quantity, :limit_price,
                        now(), :priority_sequence)
                """, params);
        return orderId;
    }

    private void 원장을_손으로_넣는다(UUID tradeId, UUID userId, String entryType,
                             long amount, long balanceAfter) {
        Map<String, Object> params = new HashMap<>();
        params.put("entry_id", UUID.randomUUID());
        params.put("user_id", userId);
        params.put("entry_type", entryType);
        params.put("amount", amount);
        params.put("balance_after", balanceAfter);
        params.put("trade_id", tradeId);
        jdbc.update("""
                INSERT INTO cash_ledger (entry_id, user_id, entry_type, amount, balance_after,
                                         trade_id, occurred_at)
                VALUES (:entry_id, :user_id, :entry_type, :amount, :balance_after, :trade_id, now())
                """, params);
    }

    private void 이벤트를_손으로_넣는다(UUID orderId, int quantity, UUID tradeId) {
        jdbc.update("""
                INSERT INTO order_events (event_id, order_id, event_type, quantity, trade_id, occurred_at)
                VALUES (:event_id, :order_id, 'FILLED', :quantity, :trade_id, now())
                """, Map.of("event_id", UUID.randomUUID(), "order_id", orderId,
                "quantity", quantity, "trade_id", tradeId));
    }

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

    /** 대사가 무언가를 건드렸다면 이 값들 중 하나는 달라진다. */
    private Map<String, Object> 전체_지문() {
        return jdbc.queryForMap("""
                SELECT (SELECT count(*) FROM orders)       AS orders,
                       (SELECT count(*) FROM order_states) AS states,
                       (SELECT count(*) FROM order_events) AS events,
                       (SELECT count(*) FROM trades)       AS trades,
                       (SELECT count(*) FROM cash_ledger)  AS ledger,
                       (SELECT coalesce(sum(balance), 0) FROM accounts)  AS balances,
                       (SELECT coalesce(sum(quantity), 0) FROM holdings) AS holdings,
                       (SELECT coalesce(sum(filled_quantity), 0) FROM order_states) AS filled
                """, Map.of());
    }
}
