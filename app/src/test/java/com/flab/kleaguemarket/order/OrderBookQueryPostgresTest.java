package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flab.kleaguemarket.domain.order.Order;
import com.flab.kleaguemarket.domain.order.RestingOrder;
import com.flab.kleaguemarket.domain.order.Side;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 호가창 조회가 {@code OrderMatcher}의 입력 계약을 만족하는지 실제 PostgreSQL에서 검증한다.
 * 매처는 정렬을 검증하지 않고 신뢰하므로 순서 규칙은 여기서만 고정된다.
 */
@SpringBootTest
@Testcontainers
class OrderBookQueryPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24");

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private static final UUID 사용자_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID 사용자_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant 기준시각 = Instant.parse("2026-08-28T00:00:00Z");
    private static final AtomicLong 선수_번호 = new AtomicLong();

    @Test
    void 매수_taker에게는_낮은_매도가부터_돌려준다() {
        long 선수 = 선수를_새로_연다();
        UUID 매도_102 = 호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 102L, 1, 0);
        UUID 매도_100 = 호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 100L, 2, 0);
        UUID 매도_101 = 호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 101L, 3, 0);

        List<RestingOrder> 호가창 = 조회한다(선수, Side.BUY, 105L);

        assertThat(호가창).extracting(RestingOrder::orderId)
                .containsExactly(매도_100, 매도_101, 매도_102);
    }

    @Test
    void 매도_taker에게는_높은_매수가부터_돌려준다() {
        long 선수 = 선수를_새로_연다();
        UUID 매수_100 = 호가를_넣는다(선수, 사용자_B, Side.BUY, 5, 100L, 1, 0);
        UUID 매수_105 = 호가를_넣는다(선수, 사용자_B, Side.BUY, 5, 105L, 2, 0);
        UUID 매수_103 = 호가를_넣는다(선수, 사용자_B, Side.BUY, 5, 103L, 3, 0);

        List<RestingOrder> 호가창 = 조회한다(선수, Side.SELL, 100L);

        assertThat(호가창).extracting(RestingOrder::orderId)
                .containsExactly(매수_105, 매수_103, 매수_100);
    }

    @Test
    void 가격이_같으면_우선순위가_앞선_주문부터_돌려준다() {
        long 선수 = 선수를_새로_연다();
        UUID 먼저 = 호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 100L, 1, 0);
        UUID 나중 = 호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 100L, 2, 0);

        assertThat(조회한다(선수, Side.BUY, 105L)).extracting(RestingOrder::orderId)
                .containsExactly(먼저, 나중);
    }

    @Test
    void 접수_시각이_우선순위와_역순이어도_우선순위가_이긴다() {
        long 선수 = 선수를_새로_연다();
        // 두 값을 일부러 어긋나게 두면 SQL이 어느 쪽으로 정렬하는지 드러난다
        UUID 우선순위_앞 = 호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 100L, 1, 0, 기준시각.plusSeconds(999));
        UUID 우선순위_뒤 = 호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 100L, 2, 0, 기준시각);

        assertThat(조회한다(선수, Side.BUY, 105L)).extracting(RestingOrder::orderId)
                .containsExactly(우선순위_앞, 우선순위_뒤);
    }

    @Test
    void 우선순위가_없는_레거시_주문은_돌려주지_않는다() {
        long 선수 = 선수를_새로_연다();
        UUID 레거시 = UUID.randomUUID();
        주문_헤더를_넣는다(레거시, 선수, 사용자_B, Side.SELL, 5, 100L, null, 기준시각);
        // 상태가 있더라도 우선순위가 없으면 정렬 위치를 정할 수 없어 제외돼야 한다
        상태를_넣는다(레거시, 5, 0, 0);

        assertThat(조회한다(선수, Side.BUY, 105L)).isEmpty();
    }

    @Test
    void 활성_잔량이_없는_주문은_돌려주지_않는다() {
        long 선수 = 선수를_새로_연다();
        호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 100L, 1, 5);          // 전량 체결
        UUID 살아있는_호가 = 호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 100L, 2, 0);
        UUID 취소된_호가 = 호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 100L, 3, 0);
        jdbc.update("UPDATE order_states SET cancelled_quantity = 5 WHERE order_id = :id",
                Map.of("id", 취소된_호가));

        assertThat(조회한다(선수, Side.BUY, 105L)).extracting(RestingOrder::orderId)
                .containsExactly(살아있는_호가);
    }

    @Test
    void 부분_체결된_주문은_남은_잔량으로_돌려준다() {
        long 선수 = 선수를_새로_연다();
        UUID 부분_체결 = 호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 100L, 1, 2);

        assertThat(조회한다(선수, Side.BUY, 105L))
                .containsExactly(new RestingOrder(부분_체결, 사용자_B, 100L, 3));
    }

    @Test
    void 다른_선수의_주문은_돌려주지_않는다() {
        long 선수 = 선수를_새로_연다();
        long 다른_선수 = 선수를_새로_연다();
        호가를_넣는다(다른_선수, 사용자_B, Side.SELL, 5, 100L, 1, 0);

        assertThat(조회한다(선수, Side.BUY, 105L)).isEmpty();
    }

    @Test
    void taker와_같은_방향의_주문은_돌려주지_않는다() {
        long 선수 = 선수를_새로_연다();
        호가를_넣는다(선수, 사용자_B, Side.BUY, 5, 100L, 1, 0);

        assertThat(조회한다(선수, Side.BUY, 105L)).isEmpty();
    }

    @Test
    void 교차하지_않는_가격의_주문은_돌려주지_않는다() {
        long 선수 = 선수를_새로_연다();
        호가를_넣는다(선수, 사용자_B, Side.SELL, 5, 106L, 1, 0);

        assertThat(조회한다(선수, Side.BUY, 105L)).isEmpty();
    }

    @Test
    void 같은_사용자의_maker도_돌려주어_STP가_처리하게_한다() {
        long 선수 = 선수를_새로_연다();
        UUID 자기_매도 = 호가를_넣는다(선수, 사용자_A, Side.SELL, 5, 100L, 1, 0);

        // SQL에서 걸러 버리면 자기 호가와 교차한 채로 끝나 크로스드 오더북이 남는다 (설계 스펙 D7)
        assertThat(조회한다(선수, Side.BUY, 105L)).extracting(RestingOrder::orderId)
                .containsExactly(자기_매도);
    }

    // ---------- fixture ----------

    /**
     * 호출마다 새 선수를 쓴다 — 같은 선수를 재사용하면 앞 테스트의 호가가 섞여 순서 단언이 흔들린다.
     * 테이블의 max로 구하면 주문을 아직 넣지 않은 사이에 두 번 부를 때 같은 번호가 나온다.
     */
    private long 선수를_새로_연다() {
        return 선수_번호.incrementAndGet();
    }

    private List<RestingOrder> 조회한다(long playerId, Side takerSide, long limitPrice) {
        Order taker = Order.placed(UUID.randomUUID(), 사용자_A, playerId, takerSide, 10, limitPrice, 기준시각);
        return new JdbcOrderBook(jdbc).activeOpposite(taker);
    }

    private UUID 호가를_넣는다(long playerId, UUID userId, Side side, int quantity, long limitPrice,
                         long prioritySequence, int filled) {
        return 호가를_넣는다(playerId, userId, side, quantity, limitPrice, prioritySequence, filled, 기준시각);
    }

    private UUID 호가를_넣는다(long playerId, UUID userId, Side side, int quantity, long limitPrice,
                         long prioritySequence, int filled, Instant createdAt) {
        UUID orderId = UUID.randomUUID();
        주문_헤더를_넣는다(orderId, playerId, userId, side, quantity, limitPrice, prioritySequence, createdAt);
        상태를_넣는다(orderId, quantity, filled, 0);
        return orderId;
    }

    private void 주문_헤더를_넣는다(UUID orderId, long playerId, UUID userId, Side side, int quantity,
                            long limitPrice, Long prioritySequence, Instant createdAt) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("order_id", orderId);
        params.put("user_id", userId);
        params.put("player_id", playerId);
        params.put("side", side.name());
        params.put("quantity", quantity);
        params.put("limit_price", limitPrice);
        params.put("created_at", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        params.put("priority_sequence", prioritySequence);
        jdbc.update("""
                INSERT INTO orders (order_id, user_id, player_id, side, quantity, limit_price,
                                    created_at, priority_sequence)
                VALUES (:order_id, :user_id, :player_id, :side, :quantity, :limit_price,
                        :created_at, :priority_sequence)
                """, params);
    }

    private void 상태를_넣는다(UUID orderId, int quantity, int filled, int cancelled) {
        jdbc.update("""
                INSERT INTO order_states (order_id, quantity, filled_quantity, cancelled_quantity)
                VALUES (:order_id, :quantity, :filled, :cancelled)
                """, Map.of("order_id", orderId, "quantity", quantity,
                "filled", filled, "cancelled", cancelled));
    }
}
