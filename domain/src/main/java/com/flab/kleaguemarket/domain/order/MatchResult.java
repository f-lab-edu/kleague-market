package com.flab.kleaguemarket.domain.order;

import java.util.List;
import java.util.Objects;

/**
 * 매칭 한 번의 결과. 정산·영속 단계의 입력이 된다 (설계 스펙 D7 — 매칭 엔진은 체결 산출까지이고
 * 잔고·보유 반영은 그다음 단계다).
 *
 * <p>maker들의 체결 후 상태를 여기에 담지 않는다. 호출자가 호가창을 이미 쥐고 있으므로
 * {@code trades}의 makerOrderId로 원래 주문을 찾아 {@link Order#withAdditionalFills(List)}를
 * 적용하면 된다. 지금 재구성해도 쓸 곳이 없는 값을 미리 만들지 않는다.
 *
 * @param taker  체결·취소가 반영된 신규 주문. 체결 수량·활성 잔량·취소 수량·상태를 여기서 읽는다
 * @param trades 이번 매칭에서 <b>새로</b> 발생한 체결만. 체결 순서(가격·시간 우선)를 유지한다
 */
public record MatchResult(Order taker, List<Trade> trades) {

    public MatchResult {
        Objects.requireNonNull(taker, "taker 주문은 null일 수 없습니다");
        Objects.requireNonNull(trades, "체결 목록은 null일 수 없습니다");
        // 호출자가 넘긴 리스트를 그대로 들고 있으면 결과를 만든 뒤에 체결 내역이 바뀔 수 있다
        trades = List.copyOf(trades);
    }
}
