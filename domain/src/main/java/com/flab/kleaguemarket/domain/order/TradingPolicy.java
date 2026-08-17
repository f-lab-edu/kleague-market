package com.flab.kleaguemarket.domain.order;

/**
 * 튜닝 상수 묶음. 운영 기본값을 여기에 심지 않는다 — 조립하는 쪽이 값을 주입한다
 * (설계 스펙 D1 — 보유 상한은 튜닝 상수).
 *
 * @param holdingCap per-user per-player 보유 상한. `보유 + 미체결 매수 잔량 + 신규 수량 ≤ cap`
 */
public record TradingPolicy(int holdingCap) {

    public TradingPolicy {
        if (holdingCap < 1) {
            throw new IllegalArgumentException("보유 상한은 1주 이상이어야 합니다: " + holdingCap);
        }
    }
}
