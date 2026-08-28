package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flab.kleaguemarket.domain.order.Side;
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
                .contains("체결마다 maker와 taker의 체결 이벤트가 수량까지 일치");
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
                .contains("현재 상태의 체결 수량이 체결 원본의 합과 일치");
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
