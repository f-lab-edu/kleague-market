package com.flab.kleaguemarket.domain.order.port;

/** 선수의 거래 가능 여부. 사유(HALTED_DELISTING·DELISTED·SUSPENDED)는 주문 판정에 영향이 없어 노출하지 않는다. */
public interface PlayerMarket {

    boolean isTradable(long playerId);
}
