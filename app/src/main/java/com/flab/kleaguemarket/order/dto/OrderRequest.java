package com.flab.kleaguemarket.order.dto;

import com.flab.kleaguemarket.domain.order.Side;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

// 래퍼 타입을 쓰는 이유: 원시 타입이면 JSON에 필드가 없어도 0으로 채워져 @NotNull을 통과한다
public record OrderRequest(
        @NotNull Long playerId,
        @NotNull Side side,
        @NotNull @Min(1) Integer quantity,
        @NotNull @Min(1) Long limitPrice
) {
}
