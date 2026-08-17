package com.flab.kleaguemarket.domain.order;

/**
 * 주문이 접수되지 못하는 사유. 상수 이름을 API 계약의 에러 code와 같게 두어
 * 어댑터가 name()만으로 매핑할 수 있게 했다 (docs/api/README.md 에러 모델).
 */
public enum OrderRejection {
    MARKET_CLOSED,
    INSUFFICIENT_BALANCE,
    INSUFFICIENT_SHARES,
    HOLDING_CAP_EXCEEDED
}
