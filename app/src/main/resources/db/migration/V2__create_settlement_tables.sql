-- 체결·자산 정산·원장을 한 트랜잭션으로 반영하기 위한 저장 모델 (ADR-0005, ADR-0006, ADR-0009).
--
-- append-only 진실의 출처: orders(V1) · trades · order_events · cash_ledger
-- 가변 projection: order_states · accounts · holdings
-- 직렬화 지점: player_order_books
--
-- V1 시절 주문은 체결됐는지 취소됐는지 알 수 없으므로 우선순위·현재 상태·접수 이벤트를 backfill하지
-- 않는다. 없는 사실을 만들면 잔고도 보유도 없는 주문이 호가창에 되살아난다.

-- ============================================================
-- orders — 신규 접수 주문의 가격-시간 우선순위 저장처
-- ============================================================
ALTER TABLE orders ADD COLUMN priority_sequence BIGINT;

ALTER TABLE orders ADD CONSTRAINT orders_priority_check
    CHECK (priority_sequence IS NULL OR priority_sequence > 0);
-- NULL끼리는 서로 충돌하지 않으므로(기본 NULLS DISTINCT) 레거시 주문 여러 건이 공존한다
ALTER TABLE orders ADD CONSTRAINT orders_priority_unique
    UNIQUE (player_id, priority_sequence);
-- order_states의 복합 외래 키가 참조할 대상
ALTER TABLE orders ADD CONSTRAINT orders_id_quantity_unique
    UNIQUE (order_id, quantity);

-- 매도 taker의 (limit_price DESC, priority_sequence ASC)는 이 인덱스로 정렬을 얻지 못하고
-- 정렬 단계를 거친다 — 정확성은 명시적 ORDER BY가 보장한다
CREATE INDEX orders_book_idx ON orders (player_id, side, limit_price, priority_sequence);

-- ============================================================
-- player_order_books — 선수별 직렬화 지점
-- ============================================================
-- UPDATE ... RETURNING 한 문장으로 잠금과 우선순위 발급을 함께 한다. 롤백되면 카운터도 되돌아가
-- 순번에 구멍이 없다 — SEQUENCE에는 없는 성질이다.
CREATE TABLE player_order_books (
    player_id     BIGINT NOT NULL,
    next_priority BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT player_order_books_pkey PRIMARY KEY (player_id),
    CONSTRAINT player_order_books_next_check CHECK (next_priority >= 0)
);

-- ============================================================
-- order_states — 주문 진행 상태 projection
-- ============================================================
-- 활성 잔량과 주문 상태는 저장하지 않고 quantity - filled - cancelled로 유도한다.
CREATE TABLE order_states (
    order_id           UUID    NOT NULL,
    quantity           INTEGER NOT NULL,
    filled_quantity    INTEGER NOT NULL DEFAULT 0,
    cancelled_quantity INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT order_states_pkey PRIMARY KEY (order_id),
    -- quantity가 orders와 달라질 수 없다. 단일 order_id 외래 키를 이것이 대신한다
    CONSTRAINT order_states_order_fkey
        FOREIGN KEY (order_id, quantity) REFERENCES orders (order_id, quantity),
    CONSTRAINT order_states_quantity_check  CHECK (quantity > 0),
    CONSTRAINT order_states_filled_check    CHECK (filled_quantity >= 0),
    CONSTRAINT order_states_cancelled_check CHECK (cancelled_quantity >= 0),
    CONSTRAINT order_states_settled_check   CHECK (filled_quantity + cancelled_quantity <= quantity),
    -- 취소는 언제나 활성 잔량 전부다. 없으면 CANCELLED로 끝난 주문에 잔량이 남는다
    CONSTRAINT order_states_cancel_all_check
        CHECK (cancelled_quantity = 0 OR filled_quantity + cancelled_quantity = quantity)
);

-- ============================================================
-- trades — 경제적 체결 원본 (append-only)
-- ============================================================
-- 공개 Fill(price, quantity)과 달리 양쪽 주문을 식별한다. 정산은 maker 쪽 자산도 움직인다.
CREATE TABLE trades (
    trade_id       UUID        NOT NULL,
    player_id      BIGINT      NOT NULL,
    maker_order_id UUID        NOT NULL,
    taker_order_id UUID        NOT NULL,
    taker_side     VARCHAR(4)  NOT NULL,
    price          BIGINT      NOT NULL,
    quantity       INTEGER     NOT NULL,
    match_sequence INTEGER     NOT NULL,
    executed_at    TIMESTAMPTZ NOT NULL,

    CONSTRAINT trades_pkey       PRIMARY KEY (trade_id),
    CONSTRAINT trades_maker_fkey FOREIGN KEY (maker_order_id) REFERENCES orders (order_id),
    CONSTRAINT trades_taker_fkey FOREIGN KEY (taker_order_id) REFERENCES orders (order_id),
    CONSTRAINT trades_price_check    CHECK (price > 0),
    CONSTRAINT trades_quantity_check CHECK (quantity > 0),
    CONSTRAINT trades_sequence_check CHECK (match_sequence > 0),
    CONSTRAINT trades_side_check     CHECK (taker_side IN ('BUY', 'SELL')),
    CONSTRAINT trades_self_check     CHECK (maker_order_id <> taker_order_id),
    CONSTRAINT trades_taker_sequence_unique UNIQUE (taker_order_id, match_sequence)
);

-- ============================================================
-- order_events — 주문 생명주기 로그 (append-only)
-- ============================================================
-- event_seq는 DB가 발급하며 주문 이력의 감사·재구성 순서다. 발급 순서라 전역 commit 순서나
-- 배당 경계를 보장하지 않는다. 중복은 순번이 아니라 아래 의미 유일 인덱스가 막는다.
CREATE TABLE order_events (
    event_id    UUID        NOT NULL,
    event_seq   BIGINT      GENERATED ALWAYS AS IDENTITY,
    order_id    UUID        NOT NULL,
    event_type  VARCHAR(16) NOT NULL,
    quantity    INTEGER     NOT NULL,
    trade_id    UUID,
    occurred_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT order_events_pkey       PRIMARY KEY (event_id),
    CONSTRAINT order_events_seq_unique UNIQUE (event_seq),
    CONSTRAINT order_events_order_fkey FOREIGN KEY (order_id) REFERENCES orders (order_id),
    CONSTRAINT order_events_trade_fkey FOREIGN KEY (trade_id) REFERENCES trades (trade_id),
    CONSTRAINT order_events_type_check     CHECK (event_type IN ('ACCEPTED', 'FILLED', 'CANCELLED')),
    CONSTRAINT order_events_quantity_check CHECK (quantity > 0),
    CONSTRAINT order_events_trade_link_check
        CHECK ((event_type = 'FILLED') = (trade_id IS NOT NULL))
);

CREATE UNIQUE INDEX order_events_accepted_unique
    ON order_events (order_id) WHERE event_type = 'ACCEPTED';
CREATE UNIQUE INDEX order_events_cancelled_unique
    ON order_events (order_id) WHERE event_type = 'CANCELLED';
CREATE UNIQUE INDEX order_events_filled_unique
    ON order_events (order_id, trade_id) WHERE event_type = 'FILLED';

-- ============================================================
-- accounts / holdings — 자산 projection
-- ============================================================
-- 예약 현금·예약 수량은 컬럼으로 두지 않고 활성 주문의 잔량에서 유도한다
-- (openapi.yaml MeResponse.reservedBalance, Holding.reservedQuantity).
-- 사용자·선수 테이블이 아직 없어 user_id·player_id에는 외래 키를 만들지 않는다.
CREATE TABLE accounts (
    user_id UUID   NOT NULL,
    balance BIGINT NOT NULL,

    CONSTRAINT accounts_pkey PRIMARY KEY (user_id),
    CONSTRAINT accounts_balance_check CHECK (balance >= 0)
);

CREATE TABLE holdings (
    user_id   UUID    NOT NULL,
    player_id BIGINT  NOT NULL,
    quantity  INTEGER NOT NULL,

    CONSTRAINT holdings_pkey PRIMARY KEY (user_id, player_id),
    CONSTRAINT holdings_quantity_check CHECK (quantity >= 0)
);

-- ============================================================
-- cash_ledger — 현금 원장 (append-only)
-- ============================================================
-- DIVIDEND·DEPOSIT은 발생 경로가 아직 없어 넣지 않는다. 수수료 컬럼도 만들지 않는다 —
-- DEFAULT 0으로라도 두면 미정인 정책을 정한 기록이 된다 (설계 스펙 D7).
CREATE TABLE cash_ledger (
    entry_id      UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    entry_type    VARCHAR(16) NOT NULL,
    amount        BIGINT      NOT NULL,
    balance_after BIGINT      NOT NULL,
    trade_id      UUID        NOT NULL,
    occurred_at   TIMESTAMPTZ NOT NULL,

    CONSTRAINT cash_ledger_pkey       PRIMARY KEY (entry_id),
    CONSTRAINT cash_ledger_trade_fkey FOREIGN KEY (trade_id) REFERENCES trades (trade_id),
    CONSTRAINT cash_ledger_type_check    CHECK (entry_type IN ('TRADE_BUY', 'TRADE_SELL')),
    CONSTRAINT cash_ledger_balance_check CHECK (balance_after >= 0),
    CONSTRAINT cash_ledger_sign_check    CHECK (
        (entry_type = 'TRADE_BUY'  AND amount < 0) OR
        (entry_type = 'TRADE_SELL' AND amount > 0)),
    -- 종류별 최대 한 건까지만 막는다. "체결마다 정확히 두 건"은 정산 트랜잭션과 대사의 몫이다
    CONSTRAINT cash_ledger_trade_type_unique UNIQUE (trade_id, entry_type)
);
