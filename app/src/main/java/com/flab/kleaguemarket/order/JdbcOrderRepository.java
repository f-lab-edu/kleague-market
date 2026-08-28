package com.flab.kleaguemarket.order;

import com.flab.kleaguemarket.domain.order.MatchResult;
import com.flab.kleaguemarket.domain.order.Order;
import com.flab.kleaguemarket.domain.order.Side;
import com.flab.kleaguemarket.domain.order.Trade;
import com.flab.kleaguemarket.domain.order.port.OrderRepository;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 주문 접수와 정산의 PostgreSQL 어댑터 (ADR-0009). 요청 사이에 남는 상태를 두지 않는다 —
 * maker의 사용자도 정산 시점에 체결의 maker 주문 ID로 한 번에 조회한다.
 */
class JdbcOrderRepository implements OrderRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcOrderRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** UPDATE 한 문장이 잠금과 발급을 함께 한다. 롤백되면 카운터도 되돌아가 순번에 구멍이 없다. */
    @Override
    public long enterSerializationPoint(long playerId) {
        jdbc.update("""
                INSERT INTO player_order_books (player_id) VALUES (:player_id)
                ON CONFLICT (player_id) DO NOTHING
                """, Map.of("player_id", playerId));
        return jdbc.queryForObject("""
                UPDATE player_order_books SET next_priority = next_priority + 1
                 WHERE player_id = :player_id
                RETURNING next_priority
                """, Map.of("player_id", playerId), Long.class);
    }

    /** 행이 없으면 조회가 예외로 끝나 트랜잭션 전체가 실패한다 — 계좌 없는 주문을 통과시키지 않는다. */
    @Override
    public void lockTraderAssets(UUID userId) {
        jdbc.queryForObject("SELECT 1 FROM accounts WHERE user_id = :user_id FOR UPDATE",
                Map.of("user_id", userId), Integer.class);
    }

    @Override
    public void saveAcceptance(Order taker, long prioritySequence) {
        Map<String, Object> header = new HashMap<>();
        header.put("order_id", taker.orderId());
        header.put("user_id", taker.userId());
        header.put("player_id", taker.playerId());
        header.put("side", taker.side().name());
        header.put("quantity", taker.quantity());
        header.put("limit_price", taker.limitPrice());
        header.put("created_at", at(taker.createdAt()));
        header.put("priority_sequence", prioritySequence);
        jdbc.update("""
                INSERT INTO orders (order_id, user_id, player_id, side, quantity, limit_price,
                                    created_at, priority_sequence)
                VALUES (:order_id, :user_id, :player_id, :side, :quantity, :limit_price,
                        :created_at, :priority_sequence)
                """, header);
        appendEvent(taker.orderId(), "ACCEPTED", taker.quantity(), null);
    }

    @Override
    public void saveSettlement(MatchResult result) {
        Order taker = result.taker();
        Map<UUID, UUID> makerUsers = makerUsers(result.trades());

        int matchSequence = 0;
        for (Trade trade : result.trades()) {
            settle(taker, trade, makerUsers.get(trade.makerOrderId()), ++matchSequence);
        }

        insertState(taker.orderId(), taker.filledQuantity(), taker.cancelledQuantity());
        if (taker.cancelledQuantity() > 0) {
            appendEvent(taker.orderId(), "CANCELLED", taker.cancelledQuantity(), null);
        }
    }

    private Map<UUID, UUID> makerUsers(List<Trade> trades) {
        if (trades.isEmpty()) {
            return Map.of();
        }
        List<UUID> makerOrderIds = trades.stream().map(Trade::makerOrderId).distinct().toList();
        Map<UUID, UUID> users = new HashMap<>();
        jdbc.query("SELECT order_id, user_id FROM orders WHERE order_id IN (:ids)",
                Map.of("ids", makerOrderIds),
                rs -> {
                    users.put(rs.getObject("order_id", UUID.class), rs.getObject("user_id", UUID.class));
                });
        return users;
    }

    private void settle(Order taker, Trade trade, UUID makerUserId, int matchSequence) {
        boolean takerBuys = taker.side() == Side.BUY;
        UUID buyer = takerBuys ? taker.userId() : makerUserId;
        UUID seller = takerBuys ? makerUserId : taker.userId();
        // 정산 금액은 체결마다 따로 계산한다. 평균 체결가는 반올림이 손실적이라 정산에 쓸 수 없다 (D4)
        long amount = Math.multiplyExact(trade.price(), trade.quantity());
        UUID tradeId = UUID.randomUUID();

        Map<String, Object> row = new HashMap<>();
        row.put("trade_id", tradeId);
        row.put("player_id", taker.playerId());
        row.put("maker_order_id", trade.makerOrderId());
        row.put("taker_order_id", taker.orderId());
        row.put("taker_side", taker.side().name());
        row.put("price", trade.price());
        row.put("quantity", trade.quantity());
        row.put("match_sequence", matchSequence);
        row.put("executed_at", at(Instant.now()));
        jdbc.update("""
                INSERT INTO trades (trade_id, player_id, maker_order_id, taker_order_id, taker_side,
                                    price, quantity, match_sequence, executed_at)
                VALUES (:trade_id, :player_id, :maker_order_id, :taker_order_id, :taker_side,
                        :price, :quantity, :match_sequence, :executed_at)
                """, row);

        appendLedger(tradeId, buyer, "TRADE_BUY", -amount);
        appendLedger(tradeId, seller, "TRADE_SELL", amount);

        jdbc.update("""
                INSERT INTO holdings (user_id, player_id, quantity)
                VALUES (:user_id, :player_id, :quantity)
                ON CONFLICT (user_id, player_id)
                DO UPDATE SET quantity = holdings.quantity + :quantity
                """, Map.of("user_id", buyer, "player_id", taker.playerId(), "quantity", trade.quantity()));
        expectOneRow(jdbc.update("""
                UPDATE holdings SET quantity = quantity - :quantity
                 WHERE user_id = :user_id AND player_id = :player_id AND quantity >= :quantity
                """, Map.of("user_id", seller, "player_id", taker.playerId(),
                "quantity", trade.quantity())), "판매자 보유 수량");

        expectOneRow(jdbc.update("""
                UPDATE order_states SET filled_quantity = filled_quantity + :quantity
                 WHERE order_id = :order_id
                   AND quantity - filled_quantity - cancelled_quantity >= :quantity
                """, Map.of("order_id", trade.makerOrderId(), "quantity", trade.quantity())),
                "maker 활성 잔량");

        appendEvent(trade.makerOrderId(), "FILLED", trade.quantity(), tradeId);
        appendEvent(taker.orderId(), "FILLED", trade.quantity(), tradeId);
    }

    /** 잔액은 읽고 계산해 쓰지 않고 원자 증분한다. balanceAfter가 DB가 알려준 실제 값이 된다. */
    private void appendLedger(UUID tradeId, UUID userId, String entryType, long amount) {
        Long balanceAfter = jdbc.queryForObject("""
                UPDATE accounts SET balance = balance + :amount WHERE user_id = :user_id
                RETURNING balance
                """, Map.of("user_id", userId, "amount", amount), Long.class);

        Map<String, Object> entry = new HashMap<>();
        entry.put("entry_id", UUID.randomUUID());
        entry.put("user_id", userId);
        entry.put("entry_type", entryType);
        entry.put("amount", amount);
        entry.put("balance_after", balanceAfter);
        entry.put("trade_id", tradeId);
        entry.put("occurred_at", at(Instant.now()));
        jdbc.update("""
                INSERT INTO cash_ledger (entry_id, user_id, entry_type, amount, balance_after,
                                         trade_id, occurred_at)
                VALUES (:entry_id, :user_id, :entry_type, :amount, :balance_after,
                        :trade_id, :occurred_at)
                """, entry);
    }

    /** 최초 수량은 헤더에서 그대로 가져온다 — 리터럴로 다시 쓰면 두 테이블이 갈라질 수 있다. */
    private void insertState(UUID orderId, int filled, int cancelled) {
        jdbc.update("""
                INSERT INTO order_states (order_id, quantity, filled_quantity, cancelled_quantity)
                SELECT order_id, quantity, :filled, :cancelled FROM orders WHERE order_id = :order_id
                """, Map.of("order_id", orderId, "filled", filled, "cancelled", cancelled));
    }

    private void appendEvent(UUID orderId, String eventType, int quantity, UUID tradeId) {
        Map<String, Object> event = new HashMap<>();
        event.put("event_id", UUID.randomUUID());
        event.put("order_id", orderId);
        event.put("event_type", eventType);
        event.put("quantity", quantity);
        event.put("trade_id", tradeId);
        event.put("occurred_at", at(Instant.now()));
        jdbc.update("""
                INSERT INTO order_events (event_id, order_id, event_type, quantity, trade_id, occurred_at)
                VALUES (:event_id, :order_id, :event_type, :quantity, :trade_id, :occurred_at)
                """, event);
    }

    /** 0행이면 잡을 물량이 없는데 잡으려 한 것이다. 조용히 넘어가면 수량이 어긋난 채 커밋된다. */
    private static void expectOneRow(int affected, String what) {
        if (affected != 1) {
            throw new IllegalStateException(what + " 갱신의 영향 행이 1이 아닙니다: " + affected);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
