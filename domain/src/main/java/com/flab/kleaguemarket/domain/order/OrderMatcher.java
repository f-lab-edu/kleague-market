package com.flab.kleaguemarket.domain.order;

import java.util.ArrayList;
import java.util.List;

/**
 * 가격·시간 우선 매칭 규칙. 신규 주문(taker)이 반대편 호가창과 어디까지 교차하는지만 판단하고,
 * 잔고·보유 이동과 영속은 하지 않는다 (설계 스펙 D7 — "매칭 엔진 = 체결 산출까지").
 *
 * <p>상태도 설정도 없는 순수 함수다. 시계·난수·해시 순회를 쓰지 않으므로 같은 입력에는 언제나
 * 같은 결과가 나온다.
 */
public final class OrderMatcher {

    private OrderMatcher() {
    }

    /**
     * 신규 주문을 호가창과 교차시킨다.
     *
     * <p><b>입력 계약</b> — {@code orderedBook}은 다음을 만족해야 한다. 이 함수는 검증하지 않는다.
     * <ol>
     *   <li>{@code taker}와 <b>같은 선수</b>의 <b>반대편</b> 주문만 담는다</li>
     *   <li><b>가격·시간 우선순위로 정렬</b>되어 있다 — 매수 taker에게는 매도 최저가부터,
     *       매도 taker에게는 매수 최고가부터, 같은 가격이면 먼저 접수된 주문부터
     *       (설계 스펙 D4 "fills 순서", ADR-0002)</li>
     *   <li>활성 잔량이 남은 주문만 담는다</li>
     * </ol>
     * 이 순서를 데이터베이스에서 만들어 전달하는 구현은 후속 이슈에서 다룬다. 정렬 계약에 기대어
     * 처음으로 교차하지 않는 주문에서 순회를 끝내므로, 정렬이 깨지면 조용히 덜 체결된다.
     *
     * <p><b>자기 주문 방지</b>는 Cancel Taker다 (설계 스펙 D7). 교차 가능한 자기 주문을 처음
     * 만나는 순간 그 시점의 taker 잔량 전부를 취소하고 매칭을 끝낸다. 그 전에 다른 사용자와
     * 체결된 분은 그대로 유지되고, 자기 maker 주문과 그 뒤의 주문은 건드리지 않는다.
     *
     * @param taker       신규 주문. 이미 체결분이 있으면 <b>활성 잔량</b>만 매칭 대상이 된다
     * @param orderedBook 위 계약을 만족하는 반대편 호가창. 이 함수는 수정하지 않는다
     * @return 체결·취소가 반영된 taker와, 이번 호출에서 새로 생긴 체결 목록
     */
    public static MatchResult match(Order taker, List<Order> orderedBook) {
        int remaining = taker.remainingQuantity();
        List<Trade> trades = new ArrayList<>();
        boolean selfTradeBlocked = false;

        for (Order maker : orderedBook) {
            if (remaining == 0) {
                break;
            }
            // 정렬돼 있으므로 여기서 교차하지 않으면 뒤의 주문은 더더욱 교차하지 않는다
            if (!crosses(taker, maker)) {
                break;
            }
            int available = maker.remainingQuantity();
            if (available == 0) {
                // 취소 사유가 이미 사라진 주문이다. 자기 주문이어도 STP를 걸지 않는다 —
                // 체결될 수 없는 호가 때문에 정상 주문이 취소되면 안 된다
                continue;
            }
            if (maker.userId().equals(taker.userId())) {
                selfTradeBlocked = true;
                break;
            }
            // 체결가는 taker가 아니라 maker의 한도가다. taker의 한도가는 교차 여부만 판정한다 (설계 스펙 D4)
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

    /** 매수 taker는 자기 한도가 이하의 매도와, 매도 taker는 자기 한도가 이상의 매수와 교차한다 (설계 스펙 D4). */
    private static boolean crosses(Order taker, Order maker) {
        return taker.side() == Side.BUY
                ? maker.limitPrice() <= taker.limitPrice()
                : maker.limitPrice() >= taker.limitPrice();
    }
}
