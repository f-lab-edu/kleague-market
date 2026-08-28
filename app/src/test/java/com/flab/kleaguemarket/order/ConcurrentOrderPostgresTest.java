package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flab.kleaguemarket.domain.order.MatchResult;
import com.flab.kleaguemarket.domain.order.Order;
import com.flab.kleaguemarket.domain.order.Side;
import com.flab.kleaguemarket.domain.order.port.OrderRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 같은 선수와 같은 계좌에 동시에 들어온 주문이 직렬화되는지 별도 connection으로 검증한다 (ADR-0005).
 *
 * <p>실행 순서는 latch로 맞추고, 뒤 요청이 실제로 잠금에서 기다렸는지는 {@code pg_blocking_pids()}로
 * 확인한다. 행 잠금 대기는 transaction ID 대기로 나타나 {@code granted = false}만으로는 불안정하다.
 * 폴링에는 시간 제한을 두어 잠금이 걸리지 않았을 때 테스트가 멈춘 채 끝나지 않게 한다.
 */
@SpringBootTest
@Import({TestOrderConfig.class, ConcurrentOrderPostgresTest.잠금_관찰_설정.class})
@Testcontainers
class ConcurrentOrderPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24");

    private static final Duration 대기_제한 = Duration.ofSeconds(10);
    private static final AtomicLong 선수_번호 = new AtomicLong(2000);

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    PlaceOrderService service;

    private final ExecutorService 실행기 = Executors.newFixedThreadPool(2);

    @AfterEach
    void 훅과_스레드를_정리한다() {
        훅.진입_후 = () -> { };
        훅.계좌_잠금_후 = () -> { };
        실행기.shutdownNow();
    }

    @Test
    void 같은_선수의_두_요청은_차례로_최신_호가창을_읽는다() throws Exception {
        long 선수 = 선수를_새로_연다();
        UUID 판매자 = 계좌를_연다(0L);
        UUID 매수자_1 = 계좌를_연다(10_000L);
        UUID 매수자_2 = 계좌를_연다(10_000L);
        보유를_넣는다(판매자, 선수, 5);
        var 매도 = service.place(new PlaceOrderCommand(판매자, 선수, Side.SELL, 5, 100L));

        CountDownLatch 앞_요청_진입 = new CountDownLatch(1);
        CountDownLatch 앞_요청_계속 = new CountDownLatch(1);
        훅.진입_후 = 한_번만(앞_요청_진입, 앞_요청_계속);

        Future<Order> 앞 = 실행기.submit(() ->
                service.place(new PlaceOrderCommand(매수자_1, 선수, Side.BUY, 5, 100L)));
        assertThat(앞_요청_진입.await(대기_제한.toSeconds(), TimeUnit.SECONDS)).isTrue();
        Future<Order> 뒤 = 실행기.submit(() ->
                service.place(new PlaceOrderCommand(매수자_2, 선수, Side.BUY, 5, 100L)));
        누군가_잠금에서_기다릴_때까지();
        앞_요청_계속.countDown();

        Order 앞_결과 = 앞.get(대기_제한.toSeconds(), TimeUnit.SECONDS);
        Order 뒤_결과 = 뒤.get(대기_제한.toSeconds(), TimeUnit.SECONDS);

        assertThat(앞_결과.filledQuantity()).isEqualTo(5);
        // 뒤 요청은 앞 요청이 커밋한 호가창을 다시 읽어 소진된 maker를 보지 못한다
        assertThat(뒤_결과.filledQuantity()).isZero();
        assertThat(뒤_결과.remainingQuantity()).isEqualTo(5);
        assertThat(체결_수량(매도.orderId())).isEqualTo(5);
        assertThat(수("SELECT count(*) FROM trades WHERE maker_order_id = :id", 매도.orderId())).isEqualTo(1);
        assertThat(수("""
                SELECT count(*) FROM cash_ledger l JOIN trades t ON t.trade_id = l.trade_id
                 WHERE t.maker_order_id = :id
                """, 매도.orderId())).isEqualTo(2);
        assertThat(잔액(판매자)).isEqualTo(500L);
    }

    @Test
    void 같은_사용자의_서로_다른_선수_주문은_예약을_중복_사용하지_못한다() throws Exception {
        long 선수_A = 선수를_새로_연다();
        long 선수_B = 선수를_새로_연다();
        UUID 매수자 = 계좌를_연다(100L);

        CountDownLatch 앞_요청_진입 = new CountDownLatch(1);
        CountDownLatch 앞_요청_계속 = new CountDownLatch(1);
        훅.계좌_잠금_후 = 한_번만(앞_요청_진입, 앞_요청_계속);

        Future<Object> 앞 = 실행기.submit(() -> 결과(매수자, 선수_A));
        assertThat(앞_요청_진입.await(대기_제한.toSeconds(), TimeUnit.SECONDS)).isTrue();
        Future<Object> 뒤 = 실행기.submit(() -> 결과(매수자, 선수_B));
        누군가_잠금에서_기다릴_때까지();
        앞_요청_계속.countDown();

        List<Object> 결과들 = List.of(앞.get(대기_제한.toSeconds(), TimeUnit.SECONDS),
                뒤.get(대기_제한.toSeconds(), TimeUnit.SECONDS));

        // 서로 다른 선수라 anchor가 다르다. 계좌 행 잠금이 없으면 둘 다 잔고 100을 보고 통과한다
        assertThat(결과들).filteredOn(r -> r instanceof Order).hasSize(1);
        assertThat(결과들).filteredOn(r -> r instanceof Exception).hasSize(1);
        assertThat(수("SELECT count(*) FROM orders WHERE user_id = :id", 매수자)).isEqualTo(1);
    }

    @Test
    void 같은_선수의_최초_주문_두_건이_동시에_들어와도_anchor는_하나다() throws Exception {
        long 선수 = 선수를_새로_연다();
        UUID 매수자_1 = 계좌를_연다(10_000L);
        UUID 매수자_2 = 계좌를_연다(10_000L);

        CountDownLatch 함께_출발 = new CountDownLatch(1);
        Future<Order> 하나 = 실행기.submit(() -> 출발_신호를_기다렸다_주문한다(함께_출발, 매수자_1, 선수));
        Future<Order> 둘 = 실행기.submit(() -> 출발_신호를_기다렸다_주문한다(함께_출발, 매수자_2, 선수));
        함께_출발.countDown();

        Order 주문_1 = 하나.get(대기_제한.toSeconds(), TimeUnit.SECONDS);
        Order 주문_2 = 둘.get(대기_제한.toSeconds(), TimeUnit.SECONDS);

        assertThat(수("SELECT count(*) FROM player_order_books WHERE player_id = :id", 선수)).isEqualTo(1);
        List<Long> 우선순위 = jdbc.queryForList("""
                SELECT priority_sequence FROM orders WHERE order_id IN (:ids) ORDER BY priority_sequence
                """, Map.of("ids", List.of(주문_1.orderId(), 주문_2.orderId())), Long.class);
        assertThat(우선순위).containsExactly(1L, 2L);
    }

    // ---------- 잠금 관찰 ----------

    /** 첫 호출자만 붙잡아 둔다. 뒤 요청은 그대로 지나가 앞 요청이 쥔 잠금에서 기다리게 된다. */
    private static Runnable 한_번만(CountDownLatch 진입, CountDownLatch 계속) {
        AtomicBoolean 첫_호출 = new AtomicBoolean(true);
        return () -> {
            if (첫_호출.compareAndSet(true, false)) {
                진입.countDown();
                try {
                    if (!계속.await(대기_제한.toSeconds(), TimeUnit.SECONDS)) {
                        throw new IllegalStateException("앞 요청을 놓아 주는 신호가 오지 않았습니다");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
    }

    private void 누군가_잠금에서_기다릴_때까지() {
        Instant 마감 = Instant.now().plus(대기_제한);
        while (Instant.now().isBefore(마감)) {
            Long 막힌_수 = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND cardinality(pg_blocking_pids(pid)) > 0
                    """, Map.of(), Long.class);
            if (막힌_수 != null && 막힌_수 > 0) {
                return;
            }
        }
        throw new IllegalStateException("제한 시간 안에 잠금 대기가 관찰되지 않았습니다");
    }

    /** 두 방향 모두 유효한 결과다 — 하나는 접수되고 하나는 잔고 부족으로 거부돼야 한다. */
    private Object 결과(UUID userId, long playerId) {
        try {
            return service.place(new PlaceOrderCommand(userId, playerId, Side.BUY, 1, 100L));
        } catch (Exception e) {
            return e;
        }
    }

    private Order 출발_신호를_기다렸다_주문한다(CountDownLatch 출발, UUID userId, long playerId) throws Exception {
        if (!출발.await(대기_제한.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("출발 신호가 오지 않았습니다");
        }
        return service.place(new PlaceOrderCommand(userId, playerId, Side.BUY, 1, 100L));
    }

    @TestConfiguration
    static class 잠금_관찰_설정 {

        @Bean
        @Primary
        OrderRepository 훅이_달린_저장소(NamedParameterJdbcTemplate jdbc) {
            return new 훅(new JdbcOrderRepository(jdbc));
        }
    }

    /** 직렬화 지점과 계좌 잠금을 잡은 직후를 관찰할 수 있게 감싼다. 그 밖의 동작은 그대로 위임한다. */
    static class 훅 implements OrderRepository {

        static volatile Runnable 진입_후 = () -> { };
        static volatile Runnable 계좌_잠금_후 = () -> { };

        private final OrderRepository 실제;

        훅(OrderRepository 실제) {
            this.실제 = 실제;
        }

        @Override
        public long enterSerializationPoint(long playerId) {
            long prioritySequence = 실제.enterSerializationPoint(playerId);
            진입_후.run();
            return prioritySequence;
        }

        @Override
        public void lockTraderAssets(UUID userId) {
            실제.lockTraderAssets(userId);
            계좌_잠금_후.run();
        }

        @Override
        public void saveAcceptance(Order taker, long prioritySequence) {
            실제.saveAcceptance(taker, prioritySequence);
        }

        @Override
        public void saveSettlement(MatchResult result) {
            실제.saveSettlement(result);
        }
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

    private int 체결_수량(UUID orderId) {
        return jdbc.queryForObject("SELECT filled_quantity FROM order_states WHERE order_id = :id",
                Map.of("id", orderId), Integer.class);
    }

    private long 수(String sql, Object id) {
        return jdbc.queryForObject(sql, Map.of("id", id), Long.class);
    }
}
