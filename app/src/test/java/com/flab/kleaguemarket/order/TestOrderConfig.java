package com.flab.kleaguemarket.order;

import com.flab.kleaguemarket.domain.order.TradingPolicy;
import com.flab.kleaguemarket.domain.order.port.MatchingEngine;
import com.flab.kleaguemarket.domain.order.port.OrderRepository;
import com.flab.kleaguemarket.domain.order.port.PlayerMarket;
import com.flab.kleaguemarket.domain.order.port.TraderAccount;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 정산 통합 테스트 전용 조립. 프로덕션 빈으로 등록하지 않는 이유는 이번 이슈의 결과물이
 * 외부 API에서 바로 쓰는 기능이 아니라 PostgreSQL 안의 정확성 기준선이기 때문이다.
 * 컨트롤러와 프로덕션 배선은 후속 이슈다.
 */
@TestConfiguration
class TestOrderConfig {

    /** 선수 테이블이 아직 없어 거래 가능 여부를 DB로 답할 수 없다. 이 번호만 정지된 것으로 다룬다. */
    static final long 거래_정지_선수 = 999_999L;

    /** 보유 상한 검사가 정산 시나리오를 가로막지 않도록 넉넉히 둔다. 제품 정책이 아니다. */
    private static final int 보유_상한 = 1_000_000;

    @Bean
    PlayerMarket playerMarket() {
        return playerId -> playerId != 거래_정지_선수;
    }

    @Bean
    TraderAccount traderAccount(NamedParameterJdbcTemplate jdbc) {
        return new JdbcTraderAccount(jdbc);
    }

    @Bean
    MatchingEngine matchingEngine(NamedParameterJdbcTemplate jdbc) {
        return new JdbcMatchingEngine(new JdbcOrderBook(jdbc));
    }

    @Bean
    OrderRepository orderRepository(NamedParameterJdbcTemplate jdbc) {
        return new JdbcOrderRepository(jdbc);
    }

    @Bean
    PlaceOrderService placeOrderService(PlayerMarket playerMarket, TraderAccount traderAccount,
                                        MatchingEngine matchingEngine, OrderRepository orderRepository) {
        return new PlaceOrderService(playerMarket, traderAccount, matchingEngine, orderRepository,
                new TradingPolicy(보유_상한));
    }
}
