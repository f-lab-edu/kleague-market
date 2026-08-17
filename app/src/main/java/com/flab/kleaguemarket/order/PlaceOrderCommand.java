package com.flab.kleaguemarket.order;

import com.flab.kleaguemarket.domain.order.Side;
import java.util.UUID;

/**
 * 주문 생성 유스케이스의 입력. 인자 5개를 나열하지 않고 묶은 이유는 playerId·quantity·limitPrice가
 * 전부 숫자라 순서를 바꿔도 컴파일이 통과하기 때문이다.
 */
public record PlaceOrderCommand(UUID userId, long playerId, Side side, int quantity, long limitPrice) {
}
