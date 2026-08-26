package com.flab.kleaguemarket.domain.order.port;

import com.flab.kleaguemarket.domain.order.MatchResult;
import com.flab.kleaguemarket.domain.order.Order;

/**
 * 신규 주문을 반대편 호가창과 교차시킨다. 호가창을 어디서 어떤 순서로 읽어 오는지가 어댑터의 몫이고,
 * 그렇게 정렬된 호가창에 적용할 순수 규칙은
 * {@link com.flab.kleaguemarket.domain.order.OrderMatcher}에 있다.
 *
 * <p>결과가 체결 목록이 아니라 {@link MatchResult}인 이유: 자기 주문 방지로 취소된 잔량과
 * 체결 상대인 maker 주문 ID는 체결가·수량만으로 표현할 수 없는데, 둘 다 정산에 필요하다
 * (설계 스펙 D7 Cancel Taker).
 *
 * <p>체결이 없으면 잔량이 그대로 호가창에 남는다 (설계 스펙 D4 — 주문 제출은 동기, 응답은
 * "접수됨 + 즉시 체결분").
 */
public interface MatchingEngine {

    MatchResult match(Order order);
}
