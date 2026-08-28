package com.flab.kleaguemarket.order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 체결 원본과 원장·이벤트·projection이 서로 맞는지 읽기만 하며 대조한다. 불일치를 찾아 돌려줄 뿐
 * 고치지 않는다 — append-only 로그를 사후에 손대면 원본이 원본이 아니게 된다 (ADR-0006).
 *
 * <p>여기서 답할 수 있는 것은 현재 상태만으로 확인되는 대조뿐이다. 트랜잭션 전후의 잔액 차이와
 * 선수 총수량 보존은 이전 상태를 알아야 하므로 정산 통합 테스트가 확인한다.
 */
class SettlementReconciliation {

    /** @param detail 불일치한 행의 식별자 */
    record Mismatch(String check, String detail) {
    }

    private static final Map<String, String> CHECKS = new LinkedHashMap<>();

    static {
        CHECKS.put("체결당 매수·매도 원장 각 한 건과 델타 합 0", """
                SELECT t.trade_id::text AS detail
                  FROM trades t
                  LEFT JOIN cash_ledger l ON l.trade_id = t.trade_id
                 GROUP BY t.trade_id
                HAVING count(*) FILTER (WHERE l.entry_type = 'TRADE_BUY') <> 1
                    OR count(*) FILTER (WHERE l.entry_type = 'TRADE_SELL') <> 1
                    OR coalesce(sum(l.amount), 0) <> 0
                """);
        CHECKS.put("원장 금액이 체결 가격 곱하기 수량", """
                SELECT l.entry_id::text AS detail
                  FROM cash_ledger l JOIN trades t ON t.trade_id = l.trade_id
                 WHERE abs(l.amount) <> t.price * t.quantity
                """);
        CHECKS.put("원장의 사용자가 실제 구매자와 판매자", """
                SELECT t.trade_id::text AS detail
                  FROM trades t
                  JOIN orders m ON m.order_id = t.maker_order_id
                  JOIN orders k ON k.order_id = t.taker_order_id
                  JOIN cash_ledger b ON b.trade_id = t.trade_id AND b.entry_type = 'TRADE_BUY'
                  JOIN cash_ledger s ON s.trade_id = t.trade_id AND s.entry_type = 'TRADE_SELL'
                 WHERE b.user_id <> CASE WHEN t.taker_side = 'BUY' THEN k.user_id ELSE m.user_id END
                    OR s.user_id <> CASE WHEN t.taker_side = 'BUY' THEN m.user_id ELSE k.user_id END
                """);
        CHECKS.put("체결 양쪽이 같은 선수의 반대 방향", """
                SELECT t.trade_id::text AS detail
                  FROM trades t
                  JOIN orders m ON m.order_id = t.maker_order_id
                  JOIN orders k ON k.order_id = t.taker_order_id
                 WHERE m.player_id <> t.player_id
                    OR k.player_id <> t.player_id
                    OR m.side = k.side
                    OR k.side <> t.taker_side
                """);
        CHECKS.put("체결 양쪽이 서로 다른 사용자", """
                SELECT t.trade_id::text AS detail
                  FROM trades t
                  JOIN orders m ON m.order_id = t.maker_order_id
                  JOIN orders k ON k.order_id = t.taker_order_id
                 WHERE m.user_id = k.user_id
                """);
        // 존재와 수량만 보면 무관한 제3의 주문이 같은 체결을 가리켜도 통과한다. 이벤트 집합이
        // 정확히 maker와 taker 둘로만 이뤄지는지 세어야 한다. trade_id가 있는 이벤트는
        // order_events_trade_link_check에 의해 반드시 FILLED이므로 종류를 따로 걸지 않는다.
        CHECKS.put("체결마다 maker와 taker의 체결 이벤트가 정확히 하나씩", """
                SELECT t.trade_id::text AS detail
                  FROM trades t
                  LEFT JOIN order_events e ON e.trade_id = t.trade_id
                 GROUP BY t.trade_id, t.maker_order_id, t.taker_order_id, t.quantity
                HAVING count(e.event_id) <> 2
                    OR count(*) FILTER (
                           WHERE e.order_id = t.maker_order_id AND e.quantity = t.quantity) <> 1
                    OR count(*) FILTER (
                           WHERE e.order_id = t.taker_order_id AND e.quantity = t.quantity) <> 1
                """);
        // 상태 테이블에서 출발하면 행이 통째로 사라진 주문은 검사 대상에서도 사라진다.
        // 그런 주문은 호가창과 예약에서 조용히 빠지므로 주문 쪽에서 출발해 존재까지 확인한다.
        CHECKS.put("신규 주문마다 현재 상태가 있고 체결 수량이 체결 원본과 일치", """
                SELECT o.order_id::text AS detail
                  FROM orders o
                  LEFT JOIN order_states s ON s.order_id = o.order_id
                 WHERE o.priority_sequence IS NOT NULL
                   AND (s.order_id IS NULL
                     OR s.filled_quantity <> (
                            SELECT coalesce(sum(t.quantity), 0) FROM trades t
                             WHERE t.maker_order_id = o.order_id OR t.taker_order_id = o.order_id))
                """);
        CHECKS.put("레거시 주문에 상태·체결·이벤트가 없음", """
                SELECT o.order_id::text AS detail
                  FROM orders o
                 WHERE o.priority_sequence IS NULL
                   AND (EXISTS (SELECT 1 FROM order_states s WHERE s.order_id = o.order_id)
                     OR EXISTS (SELECT 1 FROM order_events e WHERE e.order_id = o.order_id)
                     OR EXISTS (SELECT 1 FROM trades t
                                 WHERE t.maker_order_id = o.order_id OR t.taker_order_id = o.order_id))
                """);
    }

    private final NamedParameterJdbcTemplate jdbc;

    SettlementReconciliation(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Mismatch> findMismatches() {
        List<Mismatch> mismatches = new ArrayList<>();
        CHECKS.forEach((check, sql) ->
                jdbc.queryForList(sql, Map.of(), String.class)
                        .forEach(detail -> mismatches.add(new Mismatch(check, detail))));
        return mismatches;
    }
}
