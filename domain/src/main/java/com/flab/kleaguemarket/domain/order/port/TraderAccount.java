package com.flab.kleaguemarket.domain.order.port;

import java.util.UUID;

/**
 * 주문 판정에 필요한 잔고·보유 상태. 값마다 메서드를 두지 않고 스냅샷 하나로 읽는 이유는
 * 이 값들이 한 트랜잭션에서 함께 읽혀야 하고, 임계 구역 안의 조회를 최소화해야 하기 때문이다 (ADR-0005).
 */
public interface TraderAccount {

    Snapshot snapshot(UUID userId, long playerId);

    /**
     * @param availableBalance  가용 현금. 총 잔고에서 미체결 매수 주문의 예약분을 뺀 값 (docs/api/README.md)
     * @param heldQuantity      총 보유 주식 수. 보유 상한 판정의 기준이다
     * @param availableQuantity 가용 주식 수. 총 보유에서 미체결 매도 주문의 예약분을 뺀 값
     * @param openBuyQuantity   미체결 매수 주문의 잔량 합. 아직 보유는 아니지만 상한에는 포함된다 (설계 스펙 D1)
     */
    record Snapshot(long availableBalance, int heldQuantity, int availableQuantity, int openBuyQuantity) {
    }
}
