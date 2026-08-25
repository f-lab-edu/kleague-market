-- 주문 접수 헤더. 최초 입력을 한 번 INSERT하는 불변 테이블이며 정상 경로에서 UPDATE·DELETE하지 않는다 (ADR-0006).
-- 주문 상태·잔량·체결은 여기 저장하지 않고 이후 이벤트에서 파생한다.
--
-- created_at은 표시·감사용이다. 가격-시간 우선순위의 직렬 순서 키가 아니다 (설계 스펙 D4).
-- 사용자·선수 테이블이 아직 없으므로 외래 키는 만들지 않는다.
--
-- 적용된 이 migration은 고치지 않는다. 이후 변경은 V2 이상 새 파일로 전진한다.
CREATE TABLE orders (
    order_id    UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    player_id   BIGINT      NOT NULL,
    side        VARCHAR(4)  NOT NULL,
    -- 금액은 정수 minor-unit BIGINT, 수량은 INTEGER — 부동소수점을 쓰지 않는다
    quantity    INTEGER     NOT NULL,
    limit_price BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,

    CONSTRAINT orders_pkey PRIMARY KEY (order_id),
    -- 애플리케이션 검증의 최종 방어선 — 잘못된 값이 앱을 우회해 들어와도 DB가 거부한다
    CONSTRAINT orders_side_check CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT orders_quantity_check CHECK (quantity > 0),
    CONSTRAINT orders_limit_price_check CHECK (limit_price > 0)
);
