package com.flab.kleaguemarket.domain.order.port;

import com.flab.kleaguemarket.domain.order.MatchResult;
import com.flab.kleaguemarket.domain.order.Order;

/** 신규 주문을 반대편 호가창과 매칭해 체결·취소 결과를 반환한다. */
public interface MatchingEngine {

    MatchResult match(Order order);
}
