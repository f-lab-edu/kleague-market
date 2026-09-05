package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flab.kleaguemarket.domain.order.Side;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 정산 중간에 실패하면 주문·체결·자산·원장이 모두 이전 상태로 돌아가는지 검증한다 (설계 스펙 D4).
 *
 * <p>실패는 트랜잭션의 마지막 쓰기인 taker 상태 INSERT 직후에 주입한다. 앞쪽에서 터뜨리면 뒤 단계는
 * 애초에 실행되지 않아 "전부 롤백됐다"의 증거가 되지 못한다.
 */
@SpringBootTest
@Import(TestOrderConfig.class)
@Testcontainers
class SettlementRollbackPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24");

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PlaceOrderService service;

    private static final AtomicLong 선수_번호 = new AtomicLong(500);

    @AfterEach
    void 주입한_실패를_되돌린다() {
        jdbc.getJdbcTemplate().execute("DROP TRIGGER IF EXISTS 정산_실패_주입 ON order_states");
        jdbc.getJdbcTemplate().execute("DROP FUNCTION IF EXISTS 정산_실패()");
    }

    @Test
    void 마지막_쓰기에서_실패하면_앞선_모든_변경이_사라진다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(10_000L);
        UUID 판매자 = 계좌를_연다(0L);
        보유를_넣는다(판매자, 선수, 3);
        var 매도 = service.place(new PlaceOrderCommand(판매자, 선수, Side.SELL, 3, 100L));

        실패를_주입한다();
        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 3, 100L)))
                .hasMessageContaining("정산 실패 주입");

        // 새 트랜잭션에서 확인한다 — 같은 트랜잭션 안에서 읽으면 롤백 여부를 알 수 없다
        assertThat(수("SELECT count(*) FROM orders WHERE player_id = :id AND side = 'BUY'", 선수)).isZero();
        assertThat(수("SELECT count(*) FROM trades WHERE player_id = :id", 선수)).isZero();
        assertThat(수("""
                SELECT count(*) FROM cash_ledger l JOIN trades t ON t.trade_id = l.trade_id
                 WHERE t.player_id = :id
                """, 선수)).isZero();
        assertThat(잔액(매수자)).isEqualTo(10_000L);
        assertThat(잔액(판매자)).isZero();
        assertThat(보유(매수자, 선수)).isZero();
        assertThat(보유(판매자, 선수)).isEqualTo(3);
        assertThat(체결_수량(매도.orderId())).isZero();
        assertThat(수("""
                SELECT count(*) FROM order_events e JOIN orders o ON o.order_id = e.order_id
                 WHERE o.player_id = :id AND e.event_type = 'FILLED'
                """, 선수)).isZero();
    }

    @Test
    void 실패하면_선수_anchor의_우선순위_증가도_원복된다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(10_000L);
        service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 1, 100L));
        long anchor_이전 = anchor(선수);

        실패를_주입한다();
        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 1, 100L)))
                .hasMessageContaining("정산 실패 주입");

        assertThat(anchor(선수)).isEqualTo(anchor_이전);
    }

    @Test
    void 첫_주문이_실패하면_선수_anchor_행_자체가_남지_않는다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(10_000L);

        실패를_주입한다();
        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 1, 100L)))
                .hasMessageContaining("정산 실패 주입");

        assertThat(수("SELECT count(*) FROM player_order_books WHERE player_id = :id", 선수)).isZero();
    }

    @Test
    void 정산_중_금액이_bigint를_넘치면_전부_롤백된다() {
        long 선수 = 선수를_새로_연다();
        long 체결금액 = 1_000_000_000_000L;
        UUID 매수자 = 계좌를_연다(체결금액);
        // 이 체결을 받으면 bigint를 넘는다. 앱의 multiplyExact는 이 경로로 넘칠 수 없다 —
        // maker가 접수될 때 한도가 × 수량을 이미 통과했으므로 체결 금액은 그보다 크지 않다
        UUID 매도자 = 계좌를_연다(Long.MAX_VALUE - 1L);
        보유를_넣는다(매도자, 선수, 1_000);
        var 매수 = service.place(new PlaceOrderCommand(매수자, 선수, Side.BUY, 1_000, 1_000_000_000L));

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(매도자, 선수, Side.SELL, 1_000, 1L)))
                .hasMessageContaining("out of range");

        assertThat(수("SELECT count(*) FROM trades WHERE player_id = :id", 선수)).isZero();
        assertThat(잔액(매수자)).isEqualTo(체결금액);
        assertThat(잔액(매도자)).isEqualTo(Long.MAX_VALUE - 1L);
        assertThat(보유(매도자, 선수)).isEqualTo(1_000);
        assertThat(보유(매수자, 선수)).isZero();
        assertThat(체결_수량(매수.orderId())).isZero();
    }

    /**
     * taker 상태 INSERT가 끝난 뒤 터뜨린다. AFTER INSERT라 그 행까지 쓰인 상태에서 실패하므로
     * 트랜잭션 전체가 되돌아가야만 테스트가 통과한다.
     */
    private void 실패를_주입한다() {
        jdbc.getJdbcTemplate().execute("""
                CREATE FUNCTION 정산_실패() RETURNS TRIGGER AS
                $$ BEGIN RAISE EXCEPTION '정산 실패 주입'; END $$ LANGUAGE plpgsql
                """);
        jdbc.getJdbcTemplate().execute("""
                CREATE TRIGGER 정산_실패_주입 AFTER INSERT ON order_states
                FOR EACH ROW EXECUTE FUNCTION 정산_실패()
                """);
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

    private long anchor(long playerId) {
        return jdbc.queryForObject("""
                SELECT coalesce(max(next_priority), 0) FROM player_order_books WHERE player_id = :id
                """, Map.of("id", playerId), Long.class);
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

    private int 체결_수량(UUID orderId) {
        return jdbc.queryForObject("SELECT filled_quantity FROM order_states WHERE order_id = :id",
                Map.of("id", orderId), Integer.class);
    }

    private long 수(String sql, Object id) {
        return jdbc.queryForObject(sql, Map.of("id", id), Long.class);
    }
}
