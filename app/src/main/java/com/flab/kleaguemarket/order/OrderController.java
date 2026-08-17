package com.flab.kleaguemarket.order;

import com.flab.kleaguemarket.common.ApiException;
import com.flab.kleaguemarket.domain.order.OrderStatus;
import com.flab.kleaguemarket.domain.order.Side;
import com.flab.kleaguemarket.order.dto.OrderPage;
import com.flab.kleaguemarket.order.dto.OrderRequest;
import com.flab.kleaguemarket.order.dto.OrderResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

/**
 * ponytail: 계약(openapi.yaml)의 와이어 포맷만 고정하는 더미. 매칭·에스크로·잔고가 붙으면
 * 서비스 호출로 교체된다 — 그때 이 클래스의 고정 응답은 전부 사라진다
 */
@RestController
@RequestMapping("/api/orders")
// @Validated를 지우지 말 것. Spring 6.1+는 이게 없어도 파라미터를 검증하지만 그때는
// HandlerMethodValidationException을 던져 ApiExceptionHandler가 못 잡는다 —
// 400은 나오되 응답 봉투가 계약(ErrorResponse) 밖이 된다
@Validated
public class OrderController {

    private static final UUID KNOWN_ORDER_ID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");
    // 주문 생성 시각은 불변 사실 — Instant.now()면 같은 주문을 조회할 때마다 달라진다
    private static final Instant CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");

    @PostMapping
    public OrderResponse create(@Valid @RequestBody OrderRequest request) {
        return new OrderResponse(
                KNOWN_ORDER_ID,
                request.playerId(),
                request.side(),
                OrderStatus.OPEN,
                request.quantity(),
                0,
                request.quantity(),
                request.limitPrice(),
                List.of(),
                null,
                CREATED_AT
        );
    }

    @GetMapping
    public OrderPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                          @RequestParam(required = false) OrderStatus status) {
        return new OrderPage(page, size, 1, 1, List.of(cannedOrder(OrderStatus.OPEN, 10)));
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable UUID id) {
        requireKnown(id);
        return cannedOrder(OrderStatus.OPEN, 10);
    }

    @DeleteMapping("/{id}")
    public OrderResponse cancel(@PathVariable UUID id) {
        requireKnown(id);
        // 취소 = 잔량 취소(D4) — 예약이 반환돼 더는 체결될 수 없으므로 0
        return cannedOrder(OrderStatus.CANCELLED, 0);
    }

    private void requireKnown(UUID id) {
        if (!KNOWN_ORDER_ID.equals(id)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "주문을 찾을 수 없습니다: " + id);
        }
    }

    private OrderResponse cannedOrder(OrderStatus status, int remainingQuantity) {
        return new OrderResponse(
                KNOWN_ORDER_ID,
                1L,
                Side.BUY,
                status,
                10,
                0,
                remainingQuantity,
                15_000L,
                List.of(),
                null,
                CREATED_AT
        );
    }
}
