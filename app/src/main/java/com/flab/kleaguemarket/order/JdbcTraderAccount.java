package com.flab.kleaguemarket.order;

import com.flab.kleaguemarket.domain.order.port.TraderAccount;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 잔고·보유 projection과 활성 주문에서 유도한 예약을 한 번에 읽는다.
 *
 * <p>예약은 저장하지 않는다 — 별도 컬럼으로 두면 호가창과 어긋난다
 * (openapi.yaml MeResponse.reservedBalance).
 */
class JdbcTraderAccount implements TraderAccount {

    // 예약 현금은 선수를 가리지 않고 이 사용자의 모든 활성 매수 주문에서 나온다. 보유와 상한은
    // 선수별이다. 우선순위가 없는 레거시 주문은 활성 주문이 아니므로 예약에도 들어가지 않는다.
    private static final String SNAPSHOT = """
            WITH active AS (
                SELECT o.side, o.player_id, o.limit_price,
                       s.quantity - s.filled_quantity - s.cancelled_quantity AS remaining
                  FROM orders o
                  JOIN order_states s ON s.order_id = o.order_id
                 WHERE o.user_id = :user_id
                   AND o.priority_sequence IS NOT NULL
                   AND s.quantity - s.filled_quantity - s.cancelled_quantity > 0
            )
            SELECT a.balance - coalesce(
                       (SELECT sum(limit_price * remaining) FROM active WHERE side = 'BUY'), 0
                   ) AS available_balance,
                   coalesce(h.quantity, 0) AS held_quantity,
                   coalesce(h.quantity, 0) - coalesce(
                       (SELECT sum(remaining) FROM active
                         WHERE side = 'SELL' AND player_id = :player_id), 0
                   ) AS available_quantity,
                   coalesce(
                       (SELECT sum(remaining) FROM active
                         WHERE side = 'BUY' AND player_id = :player_id), 0
                   ) AS open_buy_quantity
              FROM accounts a
              LEFT JOIN holdings h ON h.user_id = a.user_id AND h.player_id = :player_id
             WHERE a.user_id = :user_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcTraderAccount(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Snapshot snapshot(UUID userId, long playerId) {
        return jdbc.queryForObject(SNAPSHOT, Map.of("user_id", userId, "player_id", playerId),
                (rs, rowNum) -> new Snapshot(
                        rs.getLong("available_balance"),
                        rs.getInt("held_quantity"),
                        rs.getInt("available_quantity"),
                        rs.getInt("open_buy_quantity")));
    }
}
