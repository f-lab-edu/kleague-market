package com.flab.kleaguemarket.order;

import com.flab.kleaguemarket.domain.order.MatchResult;
import com.flab.kleaguemarket.domain.order.Order;
import com.flab.kleaguemarket.domain.order.OrderMatcher;
import com.flab.kleaguemarket.domain.order.port.MatchingEngine;

/** 호가창을 읽어 순수 매칭 규칙에 넘긴다. 규칙 자체는 domain의 {@link OrderMatcher}에 있다. */
class JdbcMatchingEngine implements MatchingEngine {

    private final JdbcOrderBook orderBook;

    JdbcMatchingEngine(JdbcOrderBook orderBook) {
        this.orderBook = orderBook;
    }

    @Override
    public MatchResult match(Order order) {
        return OrderMatcher.match(order, orderBook.activeOpposite(order));
    }
}
