package com.flab.kleaguemarket.domain.order;

import java.util.ArrayList;
import java.util.List;

/** 정렬된 반대편 호가창에 가격·시간 우선과 Cancel Taker 정책을 적용한다. */
public final class OrderMatcher {

    private OrderMatcher() {
    }

    /**
     * {@code orderedBook}은 같은 선수의 반대편 활성 주문을 가격·시간 우선순위로 정렬한 목록이어야 하며,
     * 이 메서드는 정렬 여부를 검증하지 않는다.
     * 매수 taker에는 매도 최저가순, 매도 taker에는 매수 최고가순이며 같은 가격은 FIFO다.
     * 체결가는 maker 한도가를 사용하고, 교차 가능한 자기 주문을 만나면 taker 잔량을 취소하고 종료한다.
     */
    public static MatchResult match(Order taker, List<RestingOrder> orderedBook) {
        int remaining = taker.remainingQuantity();
        List<Trade> trades = new ArrayList<>();
        boolean selfTradeBlocked = false;

        for (RestingOrder maker : orderedBook) {
            if (remaining == 0) {
                break;
            }
            // 정렬되어 있으므로 이후 주문도 교차하지 않는다
            if (!crosses(taker, maker)) {
                break;
            }
            int available = maker.remainingQuantity();
            if (available == 0) {
                // 체결할 수 없는 자기 주문은 STP 대상이 아니다
                continue;
            }
            if (maker.userId().equals(taker.userId())) {
                selfTradeBlocked = true;
                break;
            }
            int quantity = Math.min(remaining, available);
            trades.add(new Trade(maker.orderId(), maker.limitPrice(), quantity));
            remaining -= quantity;
        }

        Order settled = taker.withAdditionalFills(trades.stream().map(Trade::toFill).toList());
        if (selfTradeBlocked) {
            settled = settled.cancelRemaining();
        }
        return new MatchResult(settled, trades);
    }

    private static boolean crosses(Order taker, RestingOrder maker) {
        return taker.side() == Side.BUY
                ? maker.limitPrice() <= taker.limitPrice()
                : maker.limitPrice() >= taker.limitPrice();
    }
}
