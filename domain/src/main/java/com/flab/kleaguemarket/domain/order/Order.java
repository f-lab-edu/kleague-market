package com.flab.kleaguemarket.domain.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 지정가 주문 한 건.
 *
 * <p>filledQuantity·remainingQuantity·avgFillPrice·status를 필드로 갖지 않고 fills에서 파생시킨다 —
 * 저장값으로 두면 fills와 어긋날 수 있는 상태가 생긴다 (ADR-0006의 "로그에서 파생" 원칙,
 * 설계 스펙 D4의 "언제나 원본 fills에서 재계산한다").
 *
 * <p>다만 취소만은 fills에서 파생시킬 수 없다 — "체결되지 않은 잔량이 왜 사라졌는가"는 체결 기록에
 * 남지 않는다. 그래서 cancelledQuantity만 별도 값으로 두고, 모든 인스턴스가
 * {@code 주문 수량 = 체결 수량 + 활성 잔량 + 취소 수량}을 만족하도록 생성 시점에 강제한다.
 *
 * @param quantity          최초 주문 수량. 체결돼도 줄지 않는다 — 줄어드는 것은 remainingQuantity다
 * @param limitPrice        한도가. 매수는 최대 지불, 매도는 최소 수취 (설계 스펙 D4)
 * @param cancelledQuantity 취소로 종료된 수량. 취소는 언제나 "잔량 전부"라 0이거나 잔량 전체다
 */
public record Order(
        UUID orderId,
        UUID userId,
        long playerId,
        Side side,
        int quantity,
        long limitPrice,
        List<Fill> fills,
        int cancelledQuantity,
        Instant createdAt
) {

    public Order {
        if (quantity < 1) {
            throw new IllegalArgumentException("주문 수량은 1주 이상이어야 합니다: " + quantity);
        }
        if (limitPrice < 1) {
            throw new IllegalArgumentException("한도가는 1 이상이어야 합니다: " + limitPrice);
        }
        if (cancelledQuantity < 0) {
            throw new IllegalArgumentException("취소 수량은 음수일 수 없습니다: " + cancelledQuantity);
        }
        fills = List.copyOf(fills);
        // 오버플로가 나면 조용히 음수가 되어 아래 두 검사를 모두 통과해 버린다 — 예외로 끊는다
        int settled = Math.addExact(sumQuantity(fills), cancelledQuantity);
        if (settled > quantity) {
            throw new IllegalArgumentException(
                    "체결과 취소의 합이 주문 수량을 넘을 수 없습니다: " + settled + " > " + quantity);
        }
        // 취소는 "잔량 전부"라 부분 취소가 없다. 이걸 강제하지 않으면 CANCELLED로 종료된 주문에
        // 활성 잔량이 남아 있는 모순 상태(10주 중 체결 3 · 취소 2 · 잔량 5)가 만들어진다
        if (cancelledQuantity > 0 && settled != quantity) {
            throw new IllegalArgumentException(
                    "취소된 주문에는 활성 잔량이 남을 수 없습니다: 체결 " + sumQuantity(fills)
                            + " + 취소 " + cancelledQuantity + " ≠ 주문 " + quantity);
        }
        // 설계 스펙 D4의 불변식 — "매수 실지불 ≤ 예약액이고 매도 수취 ≥ 자기 한도가(양쪽 다 한도 위반 없음)".
        // 체결가는 maker의 한도가라 이 주문의 한도가와 다를 수 있지만, 한도를 넘는 쪽으로는 갈 수 없다
        for (Fill fill : fills) {
            boolean withinLimit = side == Side.BUY ? fill.price() <= limitPrice : fill.price() >= limitPrice;
            if (!withinLimit) {
                throw new IllegalArgumentException(
                        "체결가가 " + side + " 한도가를 위반합니다: 체결 " + fill.price() + ", 한도 " + limitPrice);
            }
        }
    }

    /** 아직 취소가 없는 주문. 취소는 종료 경로에서만 생기므로 대부분의 호출부는 이 생성자를 쓴다. */
    public Order(UUID orderId, UUID userId, long playerId, Side side,
                 int quantity, long limitPrice, List<Fill> fills, Instant createdAt) {
        this(orderId, userId, playerId, side, quantity, limitPrice, fills, 0, createdAt);
    }

    /** 아직 매칭을 거치지 않은 주문. orderId는 매칭 전에 발급된다 (설계 스펙 D4 — 감사·내역·fill 참조). */
    public static Order placed(UUID orderId, UUID userId, long playerId, Side side,
                               int quantity, long limitPrice, Instant createdAt) {
        return new Order(orderId, userId, playerId, side, quantity, limitPrice, List.of(), createdAt);
    }

    /**
     * 기존 체결에 새 체결을 <b>덧붙인</b> 주문. 대체하는 짝(withFills)을 두지 않는 이유는,
     * 한 주문이 여러 번에 걸쳐 체결될 때(maker가 연속으로 잡히는 경우) 대체 의미를 잘못 쓰면
     * 과거 체결이 조용히 사라지고 정산이 어긋나기 때문이다.
     */
    public Order withAdditionalFills(List<Fill> matched) {
        if (matched.isEmpty()) {
            return this;
        }
        List<Fill> merged = new ArrayList<>(fills.size() + matched.size());
        merged.addAll(fills);
        merged.addAll(matched);
        return new Order(orderId, userId, playerId, side, quantity, limitPrice, merged, cancelledQuantity, createdAt);
    }

    /**
     * 활성 잔량 전부를 취소로 옮긴 주문. 취소는 언제나 잔량 전부이며 부분 취소는 없다 (설계 스펙 D4).
     * 활성 잔량이 이미 0이면 자기 자신을 돌려준다 — 전량 체결된 주문이 CANCELLED로 뒤집히면
     * 정산이 끝난 주문이 취소된 것으로 보인다.
     */
    public Order cancelRemaining() {
        int active = remainingQuantity();
        if (active == 0) {
            return this;
        }
        return new Order(orderId, userId, playerId, side, quantity, limitPrice, fills,
                Math.addExact(cancelledQuantity, active), createdAt);
    }

    public int filledQuantity() {
        return sumQuantity(fills);
    }

    /**
     * 계약상 이 값은 산술 잔여가 아니라 "호가창에 살아 있어 아직 체결될 수 있는 수량"이다
     * (openapi.yaml OrderResponse.remainingQuantity). 종료된 주문은 0이므로 CANCELLED에서는
     * filledQuantity + remainingQuantity &lt; quantity가 된다.
     */
    public int remainingQuantity() {
        return quantity - filledQuantity() - cancelledQuantity;
    }

    /**
     * CANCELLED를 먼저 보지만 FILLED와 겹칠 일은 없다 — 생성자가 {@code 취소 > 0}이면
     * {@code 체결 + 취소 = 주문 수량}을 강제하므로 취소가 있으면 체결은 반드시 주문 수량보다 작다.
     */
    public OrderStatus status() {
        if (cancelledQuantity > 0) {
            return OrderStatus.CANCELLED;
        }
        int filled = filledQuantity();
        if (filled == 0) {
            return OrderStatus.OPEN;
        }
        return filled < quantity ? OrderStatus.PARTIALLY_FILLED : OrderStatus.FILLED;
    }

    /**
     * 에스크로로 예약되는 "이 주문의 최대 필요 현금". 수수료 미구현인 현재는 한도가 × 잔량이다
     * (설계 스펙 D4 — 계산식이 아니라 의미가 계약이므로 정산 정책이 바뀌면 여기만 바뀐다).
     * 잔량 기준이라 신규 주문(잔량 = 주문 수량)과 미체결 잔량에 같은 식이 쓰인다.
     */
    public long maxCashRequired() {
        // 오버플로가 나면 조용히 음수가 되어 잔고 검사를 통과해 버린다 — 예외로 끊는다
        return Math.multiplyExact(limitPrice, (long) remainingQuantity());
    }

    /**
     * Σ(체결가 × 수량) ÷ Σ수량을 HALF_UP으로 정수 반올림한 표시용 값. 체결이 없으면 null.
     * 반올림이 손실적이라 정산에 쓰면 안 된다 — 금액이 필요하면 fills를 합산한다 (설계 스펙 D4).
     */
    public Long avgFillPrice() {
        int totalQuantity = filledQuantity();
        if (totalQuantity == 0) {
            return null;
        }
        long totalAmount = 0;
        for (Fill fill : fills) {
            totalAmount = Math.addExact(totalAmount, Math.multiplyExact(fill.price(), (long) fill.quantity()));
        }
        // 체결가는 양수라 (합 + 분모/2) / 분모로 HALF_UP이 나온다. 분모가 홀수면 몫이 정확히 .5일 수 없고,
        // 짝수면 분모/2가 절삭 없이 떨어지므로 두 경우 다 올바르다
        return Math.addExact(totalAmount, totalQuantity / 2) / totalQuantity;
    }

    private static int sumQuantity(List<Fill> fills) {
        int total = 0;
        for (Fill fill : fills) {
            total = Math.addExact(total, fill.quantity());
        }
        return total;
    }
}
