package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flab.kleaguemarket.domain.order.Order;
import com.flab.kleaguemarket.domain.order.Side;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V1 주문이 이미 들어 있는 DB에 V2를 적용해도 전진할 수 있는지 검증한다.
 * "로컬 DB를 지우면 된다"는 forward migration이 아니므로 실제 순서를 재현한다 —
 * Flyway 자동 실행을 끄고 V1 → 레거시 주문 삽입 → V2로 직접 적용한다.
 */
@SpringBootTest(properties = "spring.flyway.enabled=false")
@Testcontainers
class LegacyMigrationPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24");

    @Autowired
    DataSource dataSource;

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    private static final UUID 레거시_주문 = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");

    @BeforeEach
    void 빈_DB로_되돌린다() {
        Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().clean();
    }

    @Test
    void V1_주문이_있는_DB에_V2가_적용된다() {
        V1까지_적용한다();
        레거시_주문을_넣는다();

        migrate("2");

        assertThat(jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '2'", Map.of(), Boolean.class))
                .isTrue();
    }

    @Test
    void 레거시_주문은_우선순위와_현재_상태와_접수_이벤트를_얻지_않는다() {
        V1까지_적용한다();
        레거시_주문을_넣는다();

        migrate("2");

        Map<String, Object> params = Map.of("id", 레거시_주문);
        assertThat(jdbc.queryForObject(
                "SELECT priority_sequence FROM orders WHERE order_id = :id", params, Long.class))
                .isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM order_states WHERE order_id = :id", params, Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM order_events WHERE order_id = :id", params, Long.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM player_order_books", Map.of(), Long.class)).isZero();
    }

    @Test
    void 레거시_주문은_호가창_조회에서_빠진다() {
        V1까지_적용한다();
        레거시_주문을_넣는다();

        migrate("2");

        assertThat(new JdbcOrderBook(jdbc).activeOpposite(매수_taker())).isEmpty();
    }

    @Test
    void V2_이후_신규_주문은_우선순위와_현재_상태를_가진다() {
        V1까지_적용한다();
        레거시_주문을_넣는다();
        migrate("2");

        UUID 신규_주문 = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO orders (order_id, user_id, player_id, side, quantity, limit_price,
                                    created_at, priority_sequence)
                VALUES (:id, :user, 7, 'SELL', 5, 150, :now, 1)
                """, Map.of("id", 신규_주문, "user", UUID.randomUUID(),
                "now", OffsetDateTime.now(ZoneOffset.UTC)));
        jdbc.update("INSERT INTO order_states (order_id, quantity) VALUES (:id, 5)",
                Map.of("id", 신규_주문));

        assertThat(jdbc.queryForObject(
                "SELECT priority_sequence FROM orders WHERE order_id = :id",
                Map.of("id", 신규_주문), Long.class)).isEqualTo(1L);
        assertThat(new JdbcOrderBook(jdbc).activeOpposite(매수_taker()))
                .extracting(o -> o.orderId()).containsExactly(신규_주문);
    }

    /** 선수 7의 매도 호가를 잡으러 오는 신규 매수 주문. 레거시 매도 10주 @100과 교차하는 한도가다. */
    private static Order 매수_taker() {
        return Order.placed(UUID.randomUUID(), UUID.randomUUID(), 7L, Side.BUY, 10, 200L, Instant.now());
    }

    private void V1까지_적용한다() {
        migrate("1");
    }

    private void migrate(String target) {
        Flyway.configure().dataSource(dataSource).target(target).load().migrate();
    }

    /** V2를 모르는 시절에 들어온 주문 — 컬럼도 projection도 V1 형태 그대로다. */
    private void 레거시_주문을_넣는다() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("order_id", 레거시_주문);
        params.put("user_id", UUID.randomUUID());
        params.put("created_at", OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update("""
                INSERT INTO orders (order_id, user_id, player_id, side, quantity, limit_price, created_at)
                VALUES (:order_id, :user_id, 7, 'SELL', 10, 100, :created_at)
                """, params);
    }
}
