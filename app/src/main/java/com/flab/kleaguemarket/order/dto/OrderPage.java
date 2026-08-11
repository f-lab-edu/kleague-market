package com.flab.kleaguemarket.order.dto;

import java.util.List;

// ponytail: 페이지 응답이 하나뿐이라 평평한 record. /players·/me/ledger가 붙을 때 제네릭으로 묶는다
public record OrderPage(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<OrderResponse> content
) {
}
