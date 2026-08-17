package com.flab.kleaguemarket.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flab.kleaguemarket.domain.order.Fill;
import com.flab.kleaguemarket.domain.order.Order;
import com.flab.kleaguemarket.domain.order.OrderRejectedException;
import com.flab.kleaguemarket.domain.order.OrderRejection;
import com.flab.kleaguemarket.domain.order.OrderStatus;
import com.flab.kleaguemarket.domain.order.Side;
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

/**
 * Spring Context를 로드하지 않는다 — 서비스를 new로 직접 조립한다(ADR-0001의 "컨테이너 없는 빠른 테스트").
 * mock으로 세우는 것은 포트 4개(외부 경계)뿐이고, Order·Fill·TradingPolicy 같은 도메인 값은 실물을 쓴다.
 */
@ExtendWith(MockitoExtension.class)
class PlaceOrderServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
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

    @Test
    void 반대_호가가_없으면_전량이_OPEN으로_접수되고_저장된다() {
        거래가능한_선수();
        보유고(1_000_000L, 0, 0, 0);
        체결_없음();

        Order placed = service.place(매수주문(10, 15_000L));

        assertThat(placed.orderId()).isNotNull();
        assertThat(placed.status()).isEqualTo(OrderStatus.OPEN);
        assertThat(placed.fills()).isEmpty();
        assertThat(placed.filledQuantity()).isZero();
        assertThat(placed.remainingQuantity()).isEqualTo(10);
        assertThat(placed.avgFillPrice()).isNull();
        assertThat(placed.createdAt()).isNotNull();
    }

    @Test
    void 주문은_매칭을_거친_최종_상태로_저장된다() {
        거래가능한_선수();
        보유고(1_000_000L, 0, 0, 0);
        체결_없음();

        Order placed = service.place(매수주문(10, 15_000L));

        ArgumentCaptor<Order> saved = ArgumentCaptor.forClass(Order.class);
        verify(matchingEngine).match(any(Order.class));
        verify(orderRepository).save(saved.capture());
        assertThat(saved.getValue()).isEqualTo(placed);
    }

    @Test
    void 매칭_엔진이_돌려준_체결분이_주문_상태에_반영된다() {
        거래가능한_선수();
        보유고(1_000_000L, 0, 0, 0);
        // 설계 스펙 D4의 예시 — 매수 7주가 여러 가격대와 교차하면 fill마다 체결가가 다르다
        given(matchingEngine.match(any(Order.class))).willReturn(List.of(new Fill(100L, 3), new Fill(101L, 4)));

        Order placed = service.place(매수주문(7, 102L));

        assertThat(placed.status()).isEqualTo(OrderStatus.FILLED);
        assertThat(placed.filledQuantity()).isEqualTo(7);
        assertThat(placed.remainingQuantity()).isZero();
        // 704/7 = 100.57… → HALF_UP 101 (D4). 평균의 평균이 아니라 원본 fills에서 재계산한 값이다
        assertThat(placed.avgFillPrice()).isEqualTo(101L);
    }

    @Test
    void 일부만_체결되면_PARTIALLY_FILLED로_남은_수량이_잔량이_된다() {
        거래가능한_선수();
        보유고(1_000_000L, 0, 0, 0);
        given(matchingEngine.match(any(Order.class))).willReturn(List.of(new Fill(100L, 3)));

        Order placed = service.place(매수주문(10, 102L));

        assertThat(placed.status()).isEqualTo(OrderStatus.PARTIALLY_FILLED);
        assertThat(placed.filledQuantity()).isEqualTo(3);
        assertThat(placed.remainingQuantity()).isEqualTo(7);
        assertThat(placed.avgFillPrice()).isEqualTo(100L);
    }

    @Test
    void 평균_체결가는_정확히_반값이면_올림한다() {
        거래가능한_선수();
        보유고(1_000_000L, 0, 0, 0);
        // 201/2 = 100.5 — HALF_UP이면 101, HALF_EVEN이면 100이 되어 두 정책이 갈리는 지점이다 (D4는 HALF_UP)
        given(matchingEngine.match(any(Order.class))).willReturn(List.of(new Fill(100L, 1), new Fill(101L, 1)));

        Order placed = service.place(매수주문(2, 102L));

        assertThat(placed.avgFillPrice()).isEqualTo(101L);
    }

    @Test
    void 평균_체결가는_수량으로_가중한다() {
        거래가능한_선수();
        보유고(1_000_000L, 0, 0, 0);
        // D4의 반례 — 평균의 평균은 101이지만 올바른 값은 701/7 = 100.14… → 100이다
        given(matchingEngine.match(any(Order.class))).willReturn(List.of(new Fill(100L, 6), new Fill(101L, 1)));

        Order placed = service.place(매수주문(7, 102L));

        assertThat(placed.avgFillPrice()).isEqualTo(100L);
    }

    @Test
    void 한도가를_넘는_체결은_주문에_반영되지_않는다() {
        거래가능한_선수();
        보유고(1_000_000L, 0, 0, 0);
        // 매수 한도 102에 103 체결은 D4 불변식("양쪽 다 한도 위반 없음") 위반이라 도메인이 거부한다
        given(matchingEngine.match(any(Order.class))).willReturn(List.of(new Fill(103L, 1)));

        assertThatThrownBy(() -> service.place(매수주문(10, 102L)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(orderRepository, never()).save(any(Order.class));
    }

    @Test
    void 거래정지된_선수에_주문하면_MARKET_CLOSED로_거부된다() {
        given(playerMarket.isTradable(PLAYER_ID)).willReturn(false);

        주문이_거부된다(매수주문(10, 15_000L), OrderRejection.MARKET_CLOSED);

        // 선수 자체가 거래 불가라 잔고를 읽을 이유가 없다
        verify(traderAccount, never()).snapshot(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 매수_최대_필요_현금이_가용_잔고를_넘으면_INSUFFICIENT_BALANCE로_거부된다() {
        거래가능한_선수();
        보유고(149_999L, 0, 0, 0);

        // 최대 필요 현금 = 한도가 × 잔량 = 15_000 × 10 = 150_000 (설계 스펙 D4)
        주문이_거부된다(매수주문(10, 15_000L), OrderRejection.INSUFFICIENT_BALANCE);
    }

    @Test
    void 매수_최대_필요_현금이_가용_잔고와_같으면_접수된다() {
        거래가능한_선수();
        보유고(150_000L, 0, 0, 0);
        체결_없음();

        Order placed = service.place(매수주문(10, 15_000L));

        assertThat(placed.status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void 매도_수량이_가용_수량을_넘으면_INSUFFICIENT_SHARES로_거부된다() {
        거래가능한_선수();
        // 총 보유는 10주지만 9주가 다른 매도 주문에 예약돼 가용은 1주뿐이다
        보유고(0L, 10, 1, 0);

        주문이_거부된다(매도주문(2, 15_000L), OrderRejection.INSUFFICIENT_SHARES);
    }

    @Test
    void 매도_수량이_가용_수량과_같으면_접수된다() {
        거래가능한_선수();
        보유고(0L, 10, 1, 0);
        체결_없음();

        Order placed = service.place(매도주문(1, 15_000L));

        assertThat(placed.status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void 매수는_보유와_미체결_매수_잔량을_더해_보유_상한을_넘으면_HOLDING_CAP_EXCEEDED로_거부된다() {
        거래가능한_선수();
        // 보유 90 + 미체결 매수 5 + 신규 6 = 101 > 100 (설계 스펙 D1 — 보유+주문 ≤ cap)
        보유고(100_000_000L, 90, 90, 5);

        주문이_거부된다(매수주문(6, 15_000L), OrderRejection.HOLDING_CAP_EXCEEDED);
    }

    @Test
    void 보유와_미체결_매수_잔량의_합이_상한과_같으면_접수된다() {
        거래가능한_선수();
        보유고(100_000_000L, 90, 90, 5);
        체결_없음();

        Order placed = service.place(매수주문(5, 15_000L));

        assertThat(placed.status()).isEqualTo(OrderStatus.OPEN);
    }

    @Test
    void 매도는_보유_상한_검사를_받지_않는다() {
        거래가능한_선수();
        // 보유가 이미 상한이어도 파는 것은 상한을 밀어 올리지 않는다
        보유고(0L, HOLDING_CAP, HOLDING_CAP, 0);
        체결_없음();

        Order placed = service.place(매도주문(10, 15_000L));

        assertThat(placed.status()).isEqualTo(OrderStatus.OPEN);
    }

    private void 주문이_거부된다(PlaceOrderCommand command, OrderRejection expected) {
        assertThatThrownBy(() -> service.place(command))
                .isInstanceOf(OrderRejectedException.class)
                .extracting(e -> ((OrderRejectedException) e).rejection())
                .isEqualTo(expected);

        verify(matchingEngine, never()).match(any(Order.class));
        verify(orderRepository, never()).save(any(Order.class));
    }

    private void 거래가능한_선수() {
        given(playerMarket.isTradable(PLAYER_ID)).willReturn(true);
    }

    private void 보유고(long availableBalance, int heldQuantity, int availableQuantity, int openBuyQuantity) {
        given(traderAccount.snapshot(USER_ID, PLAYER_ID)).willReturn(
                new TraderAccount.Snapshot(availableBalance, heldQuantity, availableQuantity, openBuyQuantity));
    }

    private void 체결_없음() {
        given(matchingEngine.match(any(Order.class))).willReturn(List.of());
    }

    private PlaceOrderCommand 매수주문(int quantity, long limitPrice) {
        return new PlaceOrderCommand(USER_ID, PLAYER_ID, Side.BUY, quantity, limitPrice);
    }

    private PlaceOrderCommand 매도주문(int quantity, long limitPrice) {
        return new PlaceOrderCommand(USER_ID, PLAYER_ID, Side.SELL, quantity, limitPrice);
    }
}
