package com.flab.kleaguemarket.domain.order;

import java.util.Objects;
import java.util.UUID;

/** 공개 {@link Fill}에 maker 주문 ID를 더한 내부 체결 결과. */
public record Trade(UUID makerOrderId, long price, int quantity) {

    public Trade {
        Objects.requireNonNull(makerOrderId, "maker 주문 ID는 null일 수 없습니다");
        Fill.validate(price, quantity);
    }

    public Fill toFill() {
        return new Fill(price, quantity);
    }
}
