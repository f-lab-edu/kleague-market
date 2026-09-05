package com.flab.kleaguemarket.order;

import com.flab.kleaguemarket.domain.order.Order;
import com.flab.kleaguemarket.domain.order.RestingOrder;
import com.flab.kleaguemarket.domain.order.Side;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 호가창을 {@code OrderMatcher}의 입력 계약대로 읽어 온다 (설계 스펙 D4, ADR-0002).
 *
 * <p>같은 사용자의 maker를 걸러 내지 않는다. 걸러 내면 자기 호가와 교차한 채로 매칭이 끝나
 * 크로스드 오더북이 남는다 — 자기 주문 처리는 매칭 엔진의 Cancel Taker 정책이다 (설계 스펙 D7).
 */
class JdbcOrderBook {

    private static final String ACTIVE_OPPOSITE = """
            SELECT o.order_id, o.user_id, o.limit_price,
                   s.quantity - s.filled_quantity - s.cancelled_quantity AS remaining_quantity
              FROM orders o
              JOIN order_states s ON s.order_id = o.order_id
             WHERE o.player_id = :player_id
               AND o.side = :maker_side
               AND o.priority_sequence IS NOT NULL
               AND s.quantity - s.filled_quantity - s.cancelled_quantity > 0
               AND (:taker_side = 'BUY' AND o.limit_price <= :limit_price
                 OR :taker_side = 'SELL' AND o.limit_price >= :limit_price)
             ORDER BY CASE WHEN :taker_side = 'BUY' THEN o.limit_price END ASC,
                      CASE WHEN :taker_side = 'SELL' THEN o.limit_price END DESC,
                      o.priority_sequence ASC
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcOrderBook(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<RestingOrder> activeOpposite(Order taker) {
        Side makerSide = taker.side() == Side.BUY ? Side.SELL : Side.BUY;
        Map<String, Object> params = Map.of(
                "player_id", taker.playerId(),
                "maker_side", makerSide.name(),
                "taker_side", taker.side().name(),
                "limit_price", taker.limitPrice());
        return jdbc.query(ACTIVE_OPPOSITE, params, (rs, rowNum) -> new RestingOrder(
                rs.getObject("order_id", java.util.UUID.class),
                rs.getObject("user_id", java.util.UUID.class),
                rs.getLong("limit_price"),
                rs.getInt("remaining_quantity")));
    }
}
