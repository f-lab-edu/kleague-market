package com.flab.kleaguemarket.domain.order.port;

import com.flab.kleaguemarket.domain.order.MatchResult;
import com.flab.kleaguemarket.domain.order.Order;
import java.util.UUID;

/**
 * 주문 접수와 정산의 영속. 호출 순서가 정확성의 일부다 — 직렬화 지점과 계좌를 먼저 잡아야
 * 호가창과 자산을 최신 상태로 읽는다 (ADR-0005).
 */
public interface OrderRepository {

    /** 선수별 직렬화 지점에 진입하고 시간 우선순위를 발급받는다 (설계 스펙 D4). */
    long enterSerializationPoint(long playerId);

    /** 미체결 예약은 잔액을 차감하지 않으므로, 같은 사용자의 동시 주문은 이 잠금으로만 막힌다. */
    void lockTraderAssets(UUID userId);

    /** 불변 주문 헤더와 접수 이벤트. 체결이 헤더를 참조하므로 매칭 전에 저장한다. */
    void saveAcceptance(Order taker, long prioritySequence);

    /** 체결·자산·원장·양쪽 상태·종료 이벤트. 체결이 없어도 taker의 현재 상태는 저장된다. */
    void saveSettlement(MatchResult result);
}
