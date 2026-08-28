package com.flab.kleaguemarket.domain.order;

import java.util.List;
import java.util.Objects;

/**
 * 한 번의 매칭 결과.
 *
 * @param taker 체결·취소가 반영된 신규 주문
 * @param trades 이번 매칭에서 새로 발생한 체결을 가격·시간 우선순서로 담은 목록
 */
public record MatchResult(Order taker, List<Trade> trades) {

    public MatchResult {
        Objects.requireNonNull(taker, "taker 주문은 null일 수 없습니다");
        Objects.requireNonNull(trades, "체결 목록은 null일 수 없습니다");
        trades = List.copyOf(trades);
    }
}
