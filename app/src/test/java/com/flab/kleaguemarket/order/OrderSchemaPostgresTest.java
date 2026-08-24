package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 빈 실제 PostgreSQL에 Flyway V1이 적용되고, orders 스키마의 제약과 DB 트랜잭션 롤백이
 * 실제로 동작하는지 검증한다 (ADR-0009).
 *
 * <p>이 테스트가 증명하는 범위는 실행·스키마·제약·트랜잭션 기반까지다. 주문 Repository,
 * 정산 원자성, 선수별 행 잠금은 이 테스트의 범위가 아니다.
 *
 * <p>클래스에 {@code @Transactional}을 붙이지 않는다 — 바깥 자동 테스트 트랜잭션이 있으면
 * 롤백 검증이 "DB가 롤백했다"가 아니라 "테스트가 롤백했다"가 되어 무의미해진다.
 *
 * <p>{@code disabledWithoutDocker}를 쓰지 않는다 — Docker가 없으면 조용히 skip되지 않고
 * 컨테이너 기동 실패로 테스트가 실패해야 한다.
 */
@SpringBootTest
@Testcontainers
class OrderSchemaPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.11-alpine3.24");

    @Autowired
    NamedParameterJdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transactionTemplate;

    private static final String INSERT = """
            INSERT INTO orders (order_id, user_id, player_id, side, quantity, limit_price, created_at)
            VALUES (:order_id, :user_id, :player_id, :side, :quantity, :limit_price, :created_at)
            """;

    /** 클래스 전체가 한 컨테이너를 공유하므로 앞 테스트가 남긴 행을 지운다 — 테스트 전용 정리다. */
    @BeforeEach
    void 남은_행을_지운다() {
        jdbc.getJdbcTemplate().execute("DELETE FROM orders");
    }

    @Test
    void 빈_DB에_Flyway_V1이_적용되어_orders가_생성된다() {
        Map<String, Object> history = jdbc.queryForMap(
                "SELECT version, success FROM flyway_schema_history WHERE version = '1'", Map.of());

        assertThat(history.get("success")).isEqualTo(true);
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('public.orders') IS NOT NULL", Map.of(), Boolean.class)).isTrue();
    }

    @Test
    void 유효한_주문은_INSERT되고_모든_컬럼이_마이크로초까지_그대로_조회된다() {
        // PostgreSQL TIMESTAMPTZ는 마이크로초까지 저장한다 — 나노초 자리를 넣으면 무엇이 잘렸는지 알 수 없다
        Instant createdAt = Instant.parse("2026-08-24T12:34:56.123456Z");
        Map<String, Object> order = 주문(UUID.randomUUID());
        order.put("created_at", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));

        jdbc.update(INSERT, order);

        Map<String, Object> saved = jdbc.queryForMap(
                "SELECT * FROM orders WHERE order_id = :order_id",
                Map.of("order_id", order.get("order_id")));
        assertThat(saved.get("user_id")).isEqualTo(order.get("user_id"));
        assertThat(saved.get("player_id")).isEqualTo(order.get("player_id"));
        assertThat(saved.get("side")).isEqualTo("BUY");
        assertThat(saved.get("quantity")).isEqualTo(10);
        assertThat(saved.get("limit_price")).isEqualTo(15_000L);
        // created_at은 타입을 지정해 다시 읽는다 — SELECT * 매핑은 TIMESTAMPTZ를 java.sql.Timestamp로
        // 내려주므로, 이 테스트가 확인하려는 "마이크로초가 살아 있는가"가 타입 변환에 가려진다
        assertThat(jdbc.queryForObject(
                "SELECT created_at FROM orders WHERE order_id = :order_id",
                Map.of("order_id", order.get("order_id")), OffsetDateTime.class).toInstant())
                .isEqualTo(createdAt);
    }

    @Test
    void 같은_order_id를_두_번_INSERT하면_기본_키_제약이_거부한다() {
        UUID orderId = UUID.randomUUID();
        jdbc.update(INSERT, 주문(orderId));

        assertThatThrownBy(() -> jdbc.update(INSERT, 주문(orderId)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "order_id", "user_id", "player_id", "side", "quantity", "limit_price", "created_at"})
    void 필수_컬럼이_null이면_NOT_NULL_제약이_거부한다(String column) {
        Map<String, Object> order = 주문(UUID.randomUUID());
        order.put(column, null);

        assertThatThrownBy(() -> jdbc.update(INSERT, order))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void side가_BUY도_SELL도_아니면_검사_제약이_거부한다() {
        Map<String, Object> order = 주문(UUID.randomUUID());
        order.put("side", "HOLD");

        assertThatThrownBy(() -> jdbc.update(INSERT, order))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void 수량이_양수가_아니면_검사_제약이_거부한다(int quantity) {
        Map<String, Object> order = 주문(UUID.randomUUID());
        order.put("quantity", quantity);

        assertThatThrownBy(() -> jdbc.update(INSERT, order))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 한도가가_양수가_아니면_검사_제약이_거부한다(long limitPrice) {
        Map<String, Object> order = 주문(UUID.randomUUID());
        order.put("limit_price", limitPrice);

        assertThatThrownBy(() -> jdbc.update(INSERT, order))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 한_트랜잭션의_두_번째_INSERT가_실패하면_첫_번째_INSERT도_남지_않는다() {
        UUID 살아남으면_안_되는_주문 = UUID.randomUUID();
        Map<String, Object> 위반하는_주문 = 주문(UUID.randomUUID());
        위반하는_주문.put("side", "HOLD");

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            jdbc.update(INSERT, 주문(살아남으면_안_되는_주문));
            jdbc.update(INSERT, 위반하는_주문);
        })).isInstanceOf(DataIntegrityViolationException.class);

        // 트랜잭션 밖의 새 조회로 확인한다 — 같은 트랜잭션 안에서 읽으면 롤백 여부를 알 수 없다
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM orders", Map.of(), Long.class)).isZero();
    }

    /** 제약을 하나씩 깨보려면 나머지 컬럼이 모두 유효한 기준 주문이 필요하다. */
    private static Map<String, Object> 주문(UUID orderId) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("order_id", orderId);
        order.put("user_id", UUID.randomUUID());
        order.put("player_id", 1L);
        order.put("side", "BUY");
        order.put("quantity", 10);
        order.put("limit_price", 15_000L);
        order.put("created_at", OffsetDateTime.now(ZoneOffset.UTC));
        return order;
    }
}
