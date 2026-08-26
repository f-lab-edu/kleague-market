package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flab.kleaguemarket.domain.order.Fill;
import com.flab.kleaguemarket.domain.order.MatchResult;
import com.flab.kleaguemarket.domain.order.Order;
import com.flab.kleaguemarket.domain.order.OrderRejectedException;
import com.flab.kleaguemarket.domain.order.OrderRejection;
import com.flab.kleaguemarket.domain.order.Side;
import com.flab.kleaguemarket.domain.order.Trade;
import com.flab.kleaguemarket.domain.order.TradingPolicy;
import com.flab.kleaguemarket.domain.order.port.MatchingEngine;
import com.flab.kleaguemarket.domain.order.port.OrderRepository;
import com.flab.kleaguemarket.domain.order.port.PlayerMarket;
import com.flab.kleaguemarket.domain.order.port.TraderAccount;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;

/**
 * place()가 직접 가진 분기와 협력자 조율만 검증한다 — 거부 판정 네 갈래, 포트 호출과 중단,
 * 매칭 결과가 반영된 주문이 저장·반환되는지까지다.
 *
 * <p>status·filledQuantity·remainingQuantity·avgFillPrice·maxCashRequired는 여기서 단언하지 않는다.
 * fills에서 파생되는 Order의 규칙이라 domain 모듈의 OrderTest가 검증한다 (ADR-0001).
 *
 * <p>Spring Context를 로드하지 않는다 — 서비스를 new로 직접 조립한다. mock으로 세우는 것은
 * 포트 4개(외부 경계)뿐이고 Order·Fill·TradingPolicy 같은 도메인 값은 실물을 쓴다.
 *
 * <p>반드시 일어나야 하는 호출은 verify()로, 일어나면 안 되는 호출은 never()로 적는다.
 * Mockito의 strict stubs는 선언해 놓고 안 쓴 given을 잡아줄 뿐 호출 계약을 보장하지 않는다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceOrderServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MAKER_ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final long PLAYER_ID = 42L;
    private static final int HOLDING_CAP = 100;

    @Mock
    private PlayerMarket playerMarket;
    @Mock
    private TraderAccount traderAccount;
    @Mock
    private MatchingEngine matchingEngine;
    @Mock
    private OrderRepository orderRepository;

    private PlaceOrderService service;

    @BeforeEach
    void setUp() {
        service = new PlaceOrderService(
                playerMarket, traderAccount, matchingEngine, orderRepository, new TradingPolicy(HOLDING_CAP));
    }

    /**
     * 넘겨받은 주문에 주어진 체결을 반영해 돌려주는 매칭 엔진 스텁.
     *
     * <p>고정된 MatchResult를 돌려주지 않는 이유: 주문 id는 서비스가 매칭 직전에 발급하므로
     * 테스트가 미리 알 수 없다. 인자를 받아 만들어야 "매칭에 넘긴 주문"과 "매칭이 돌려준 주문"이
     * 같은 주문이라는 전제가 성립한다.
     *
     * <p>maker 주문 ID를 채운 trades까지 만들어 두는 것은 place()가 그것을 흘려버리지 않는지
     * 보기 위해서다 — 지금은 taker만 쓰지만 결과에서 잘못된 값을 꺼내면 여기서 드러난다.
     */
    private static Answer<MatchResult> 체결시킨다(List<Fill> fills) {
        return invocation -> {
            Order requested = invocation.getArgument(0);
            List<Trade> trades = fills.stream()
                    .map(fill -> new Trade(MAKER_ORDER_ID, fill.price(), fill.quantity()))
                    .toList();
            return new MatchResult(requested.withAdditionalFills(fills), trades);
        };
    }

    @Test
    void 필요_현금과_보유_상한_경계의_매수는_매칭_결과가_반영된_채로_저장되고_반환된다() {
        given(playerMarket.isTradable(PLAYER_ID)).willReturn(true);
        // 최대 필요 현금 100 × 10 = 1_000 = 가용 잔고, 예상 보유 80 + 10 + 10 = 100 = 상한 — 두 경계를 동시에 밟는다
        given(traderAccount.snapshot(USER_ID, PLAYER_ID))
                .willReturn(new TraderAccount.Snapshot(1_000L, 80, 0, 10));
        List<Fill> matched = List.of(new Fill(90L, 4));
        given(matchingEngine.match(any(Order.class))).willAnswer(체결시킨다(matched));

        Order placed = service.place(new PlaceOrderCommand(USER_ID, PLAYER_ID, Side.BUY, 10, 100L));

        // orderId는 매칭 전에 발급된다 (설계 스펙 D4 — 감사·내역·fill 참조)
        assertThat(placed.orderId()).isNotNull();
        assertThat(placed.fills()).isEqualTo(matched);

        // 매칭 엔진에 넘긴 주문이 명령 그대로인지 본다. any()로만 두면 엉뚱한 주문을 매칭에 보내고
        // 돌려받은 체결을 원래 주문에 반영해 저장해도 통과한다
        ArgumentCaptor<Order> requested = ArgumentCaptor.forClass(Order.class);
        verify(matchingEngine).match(requested.capture());
        assertThat(requested.getValue().userId()).isEqualTo(USER_ID);
        assertThat(requested.getValue().playerId()).isEqualTo(PLAYER_ID);
        assertThat(requested.getValue().side()).isEqualTo(Side.BUY);
        assertThat(requested.getValue().quantity()).isEqualTo(10);
        assertThat(requested.getValue().limitPrice()).isEqualTo(100L);
        // orderId는 매칭 전에 발급된다 (설계 스펙 D4) — 매칭에 넘긴 주문과 반환된 주문의 id가 같아야 한다
        assertThat(requested.getValue().orderId()).isEqualTo(placed.orderId());

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(saved.capture());
        // 매칭 전 주문을 저장하고 매칭 후 주문을 반환하면 DB와 응답이 어긋난다. 체결이 비어 있으면
        // 두 주문이 같아져 이 회귀를 못 잡으므로 이 시나리오의 체결은 비어 있지 않아야 한다
        assertThat(saved.getValue()).isEqualTo(placed);
    }

    @Test
    void 보유_상한에_도달한_매도도_가용_수량과_같으면_접수된다() {
        given(playerMarket.isTradable(PLAYER_ID)).willReturn(true);
        // 보유가 이미 상한이어도 파는 것은 상한을 밀어 올리지 않는다. 상한 계산을 매도에도 적용하면
        // 100 + 100 > 100이 되어 거부된다 — 그 오류를 이 입력이 잡는다
        given(traderAccount.snapshot(USER_ID, PLAYER_ID))
                .willReturn(new TraderAccount.Snapshot(0L, HOLDING_CAP, 100, 0));
        given(matchingEngine.match(any(Order.class))).willAnswer(체결시킨다(List.of()));

        Order placed = service.place(new PlaceOrderCommand(USER_ID, PLAYER_ID, Side.SELL, 100, 100L));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(matchingEngine).match(any(Order.class));
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(placed);
    }

    @Test
    void 거래정지된_선수에_주문하면_MARKET_CLOSED로_거부되고_잔고도_읽지_않는다() {
        given(playerMarket.isTradable(PLAYER_ID)).willReturn(false);

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(USER_ID, PLAYER_ID, Side.BUY, 10, 100L)))
                .isInstanceOf(OrderRejectedException.class)
                .extracting(e -> ((OrderRejectedException) e).rejection())
                .isEqualTo(OrderRejection.MARKET_CLOSED);

        // 선수 자체가 거래 불가라 잔고를 읽을 이유가 없다 — 거부는 가장 바깥 조건에서 끝난다
        verify(traderAccount, never()).snapshot(any(), anyLong());
        verify(matchingEngine, never()).match(any(Order.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void 매수_최대_필요_현금이_가용_잔고보다_1_크면_INSUFFICIENT_BALANCE로_거부된다() {
        given(playerMarket.isTradable(PLAYER_ID)).willReturn(true);
        // 최대 필요 현금 100 × 10 = 1_000, 가용 잔고 999 — 딱 1 모자란 경계
        given(traderAccount.snapshot(USER_ID, PLAYER_ID))
                .willReturn(new TraderAccount.Snapshot(999L, 0, 0, 0));

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(USER_ID, PLAYER_ID, Side.BUY, 10, 100L)))
                .isInstanceOf(OrderRejectedException.class)
                .extracting(e -> ((OrderRejectedException) e).rejection())
                .isEqualTo(OrderRejection.INSUFFICIENT_BALANCE);

        verify(matchingEngine, never()).match(any(Order.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void 매수_예상_보유량이_상한보다_1_크면_HOLDING_CAP_EXCEEDED로_거부된다() {
        given(playerMarket.isTradable(PLAYER_ID)).willReturn(true);
        // 잔고는 넉넉하다 — 걸리는 것은 예상 보유 80 + 11 + 10 = 101로 상한보다 1 큰 쪽이다 (설계 스펙 D1)
        given(traderAccount.snapshot(USER_ID, PLAYER_ID))
                .willReturn(new TraderAccount.Snapshot(1_000_000L, 80, 0, 11));

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(USER_ID, PLAYER_ID, Side.BUY, 10, 100L)))
                .isInstanceOf(OrderRejectedException.class)
                .extracting(e -> ((OrderRejectedException) e).rejection())
                .isEqualTo(OrderRejection.HOLDING_CAP_EXCEEDED);

        verify(matchingEngine, never()).match(any(Order.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void 매도_수량이_가용_수량보다_1_크면_INSUFFICIENT_SHARES로_거부된다() {
        given(playerMarket.isTradable(PLAYER_ID)).willReturn(true);
        // 총 보유는 10주지만 1주가 다른 매도 주문에 예약돼 가용은 9주 — 10주 주문은 1 초과다
        given(traderAccount.snapshot(USER_ID, PLAYER_ID))
                .willReturn(new TraderAccount.Snapshot(0L, 10, 9, 0));

        assertThatThrownBy(() -> service.place(new PlaceOrderCommand(USER_ID, PLAYER_ID, Side.SELL, 10, 100L)))
                .isInstanceOf(OrderRejectedException.class)
                .extracting(e -> ((OrderRejectedException) e).rejection())
                .isEqualTo(OrderRejection.INSUFFICIENT_SHARES);

        verify(matchingEngine, never()).match(any(Order.class));
        verify(orderRepository, never()).save(any(Order.class));
    }
}
