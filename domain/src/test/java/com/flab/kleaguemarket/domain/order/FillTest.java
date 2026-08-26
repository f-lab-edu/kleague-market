package com.flab.kleaguemarket.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 체결 한 건의 불변식만 검증한다. "체결 가격과 수량은 항상 양수"는 매칭이 지켜야 할 규칙이지만,
 * 매칭 바깥에서 만들어진 Fill에도 똑같이 적용돼야 하므로 값 자체에 못박는다.
 *
 * <p>record component(price·quantity)는 그대로라 공개 API 계약(openapi.yaml Fill)은 바뀌지 않는다.
 */
class FillTest {

    @Test
    void 최솟값인_1은_허용된다() {
        // 경계다 — 검사를 price > 1로 잘못 쓰면 1에 체결된 정상 체결이 거부된다
        Fill fill = new Fill(1L, 1);

        assertEquals(1L, fill.price());
        assertEquals(1, fill.quantity());
    }

    @Test
    void 가격이_양수가_아니면_거부한다() {
        assertThrows(IllegalArgumentException.class, () -> new Fill(0L, 3));
    }

    @Test
    void 수량이_양수가_아니면_거부한다() {
        // 수량 0인 체결은 원장에 아무 의미가 없으면서 체결 건수만 늘린다
        assertThrows(IllegalArgumentException.class, () -> new Fill(100L, 0));
    }
}
