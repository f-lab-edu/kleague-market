package com.flab.kleaguemarket.domain.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class FillTest {

    @Test
    void 최솟값인_1은_허용된다() {
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
        assertThrows(IllegalArgumentException.class, () -> new Fill(100L, 0));
    }
}
