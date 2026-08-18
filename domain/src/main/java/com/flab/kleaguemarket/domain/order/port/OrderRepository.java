package com.flab.kleaguemarket.domain.order.port;

import com.flab.kleaguemarket.domain.order.Order;

public interface OrderRepository {

    /** 즉시 100% 체결돼도 주문 레코드는 영속된다 — 감사·내역·fill 참조 때문이다 (설계 스펙 D4). */
    void save(Order order);
}
