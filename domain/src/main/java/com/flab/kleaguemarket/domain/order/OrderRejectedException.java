package com.flab.kleaguemarket.domain.order;

/**
 * app의 ApiException을 쓰지 않는 이유: 그쪽은 HttpStatus를 필드로 들고 있어
 * domain이 참조하면 Spring 의존이 생긴다 (ADR-0001 — 컴파일 자체가 막힌다).
 */
public class OrderRejectedException extends RuntimeException {

    private final transient OrderRejection rejection;

    public OrderRejectedException(OrderRejection rejection, String message) {
        super(message);
        this.rejection = rejection;
    }

    public OrderRejection rejection() {
        return rejection;
    }
}
