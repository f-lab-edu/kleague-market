package com.flab.kleaguemarket.domain.order;

/** 체결 한 건. price는 maker의 한도가이지 이 주문의 한도가가 아니다 (설계 스펙 D4). */
public record Fill(long price, int quantity) {
}
