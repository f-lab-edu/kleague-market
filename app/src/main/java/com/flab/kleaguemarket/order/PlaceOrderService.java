package com.flab.kleaguemarket.order;

import com.flab.kleaguemarket.domain.order.Order;
import com.flab.kleaguemarket.domain.order.OrderRejectedException;
import com.flab.kleaguemarket.domain.order.OrderRejection;
import com.flab.kleaguemarket.domain.order.Side;
import com.flab.kleaguemarket.domain.order.TradingPolicy;
import com.flab.kleaguemarket.domain.order.port.MatchingEngine;
import com.flab.kleaguemarket.domain.order.port.OrderRepository;
import com.flab.kleaguemarket.domain.order.port.PlayerMarket;
import com.flab.kleaguemarket.domain.order.port.TraderAccount;
import java.time.Instant;
import java.util.UUID;

/**
 * 주문 생성 유스케이스 — 조율과 트랜잭션 경계는 app의 몫이다 (ADR-0001).
 *
 * <p>@Service를 붙이지 않은 것은 의도다. 포트 4개의 어댑터가 아직 없어 빈으로 등록하면
 * 컨텍스트 조립이 실패한다. 컨트롤러를 실제 서비스로 교체할 때 함께 등록한다.
 *
 * <p>@Transactional도 아직 없다. 주문 생성 → 매칭 → 정산 → 잔량 등록이 단일 원자 트랜잭션이어야 하고
 * 선수별로 직렬화돼야 하지만(ADR-0005), 그 경계는 실제 Repository와 매칭 엔진이 붙을 때 결정된다.
 */
public class PlaceOrderService {

    private final PlayerMarket playerMarket;
    private final TraderAccount traderAccount;
    private final MatchingEngine matchingEngine;
    private final OrderRepository orderRepository;
    private final TradingPolicy policy;

    public PlaceOrderService(PlayerMarket playerMarket,
                             TraderAccount traderAccount,
                             MatchingEngine matchingEngine,
                             OrderRepository orderRepository,
                             TradingPolicy policy) {
        this.playerMarket = playerMarket;
        this.traderAccount = traderAccount;
        this.matchingEngine = matchingEngine;
        this.orderRepository = orderRepository;
        this.policy = policy;
    }

    /**
     * 주문을 접수한다. 반환값은 "체결됨"이 아니라 "접수됨 + 즉시 체결분"이다 (설계 스펙 D4).
     *
     * @throws OrderRejectedException 거래정지·잔고 부족·주식 부족·보유 상한 초과
     */
    public Order place(PlaceOrderCommand command) {
        Order order = Order.placed(
                UUID.randomUUID(),
                command.userId(),
                command.playerId(),
                command.side(),
                command.quantity(),
                command.limitPrice(),
                Instant.now());

        rejectIfUnacceptable(order);

        Order settled = matchingEngine.match(order).taker();
        orderRepository.save(settled);
        return settled;
    }

    /**
     * 접수 가능한지 판정한다. 검사 순서를 고정한 이유는 한 요청이 여러 조건을 동시에 위반할 때
     * 응답 code가 실행마다 달라지지 않게 하기 위해서다. 거래정지가 가장 바깥 조건이라 먼저 본다.
     */
    private void rejectIfUnacceptable(Order order) {
        if (!playerMarket.isTradable(order.playerId())) {
            throw new OrderRejectedException(OrderRejection.MARKET_CLOSED,
                    "거래가 정지된 선수입니다: " + order.playerId());
        }

        TraderAccount.Snapshot account = traderAccount.snapshot(order.userId(), order.playerId());

        if (order.side() == Side.BUY) {
            long required = order.maxCashRequired();
            if (required > account.availableBalance()) {
                throw new OrderRejectedException(OrderRejection.INSUFFICIENT_BALANCE,
                        "가용 잔고가 부족합니다: 필요 " + required + ", 가용 " + account.availableBalance());
            }
            // 미체결 매수 잔량은 아직 보유가 아니지만 체결되면 보유가 되므로 함께 센다 (설계 스펙 D1)
            int projected = Math.addExact(
                    Math.addExact(account.heldQuantity(), account.openBuyQuantity()), order.quantity());
            if (projected > policy.holdingCap()) {
                throw new OrderRejectedException(OrderRejection.HOLDING_CAP_EXCEEDED,
                        "선수별 보유 상한을 초과합니다: " + projected + " > " + policy.holdingCap());
            }
        } else if (order.quantity() > account.availableQuantity()) {
            throw new OrderRejectedException(OrderRejection.INSUFFICIENT_SHARES,
                    "가용 주식이 부족합니다: 필요 " + order.quantity() + ", 가용 " + account.availableQuantity());
        }
    }
}
