package com.flab.kleaguemarket.domain.order.port;

import com.flab.kleaguemarket.domain.order.Fill;
import com.flab.kleaguemarket.domain.order.Order;
import java.util.List;

/**
 * 신규 주문을 반대편 호가창과 교차시킨다. 반환값은 즉시 체결분이며, 비어 있으면 잔량이 그대로 호가창에 남는다
 * (설계 스펙 D4 — 주문 제출은 동기, 응답은 "접수됨 + 즉시 체결분").
 */
public interface MatchingEngine {

    List<Fill> match(Order order);
}
