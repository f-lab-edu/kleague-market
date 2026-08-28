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
        CHECKS.put("체결마다 maker와 taker의 체결 이벤트가 수량까지 일치", """
                SELECT t.trade_id::text AS detail
                  FROM trades t
                  LEFT JOIN order_events em
                         ON em.trade_id = t.trade_id AND em.order_id = t.maker_order_id
                  LEFT JOIN order_events ek
                         ON ek.trade_id = t.trade_id AND ek.order_id = t.taker_order_id
                 WHERE em.event_id IS NULL OR ek.event_id IS NULL
                    OR em.quantity <> t.quantity OR ek.quantity <> t.quantity
                """);
        CHECKS.put("현재 상태의 체결 수량이 체결 원본의 합과 일치", """
                SELECT s.order_id::text AS detail
                  FROM order_states s
                 WHERE s.filled_quantity <> (
                           SELECT coalesce(sum(t.quantity), 0) FROM trades t
                            WHERE t.maker_order_id = s.order_id OR t.taker_order_id = s.order_id)
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
