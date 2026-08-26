package com.flab.kleaguemarket.domain.order;

import java.util.Objects;
import java.util.UUID;

/**
 * 매칭이 만들어 낸 체결 한 건의 <b>내부</b> 표현. 공개 계약의 {@link Fill}과 달리 상대(maker)가 누구인지를
 * 함께 담는다 — 정산은 maker 쪽 주문·계좌도 움직여야 하는데 {@code Fill}에는 그 정보가 없다.
 *
 * <p>공개 API의 {@code Fill} 구조를 늘리지 않는 이유는 그쪽이 응답 스키마(openapi.yaml Fill)와
 * 1:1이기 때문이다. 주문자에게 상대 주문 ID를 노출할 이유가 없다.
 *
 * @param makerOrderId 체결 상대인 기존 주문. 정산이 이 id로 maker를 되찾는다
 * @param price        체결가. 언제나 maker의 한도가다 (설계 스펙 D4)
 */
public record Trade(UUID makerOrderId, long price, int quantity) {

    public Trade {
        Objects.requireNonNull(makerOrderId, "maker 주문 ID는 null일 수 없습니다");
        Fill.validate(price, quantity);
    }

    /** 주문에 기록될 공개 체결로 투영한다. maker 주문 ID는 여기서 떨어져 나간다. */
    public Fill toFill() {
        return new Fill(price, quantity);
    }
}
