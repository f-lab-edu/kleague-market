# kleague-market API 요약 (MVP)

기계용 계약은 [[openapi.yaml]]. 이건 사람이 한눈에 보는 요약. 설계 근거는 [[2026-07-29-kleague-market-design]].

## 공통 규약

- **Base**: `/api`
- **가격 단위**: 정수 **minor-unit**(포인트 ×100). **money 필드는 long(int64)**(오버플로 방지), **quantity(주식 수)는 int**(고정 공급이라 작음)
- **인증**: 헤더 `Authorization: Bearer <token>`(서버측 opaque 토큰). 공개 엔드포인트는 토큰 불필요. 토큰은 **만료**되며(`expiresIn` 초) 로그아웃 시 즉시 무효화
- **식별자**: 공개 id는 전부 **내부 id**. api-sports id는 내부 매핑 전용(미노출) → 제공자 교체해도 공개 id 불변. **타입은 도메인 엔티티(선수·팀·경기) = `long(int64)`, 사용자·주문 = `uuid(string)`** — 숫자 id를 int32로 두지 않는다
- **시간**: 모든 시각 **ISO-8601 UTC**(`2026-08-05T12:34:56Z`) — 저장·직렬화 UTC 통일
- **페이징**: `page`(0-base, ≥0)·`size`(1~100), 응답에 `page/size/totalElements/totalPages`. Spring Data 스타일 필드지만 **자체 DTO로 직렬화**(Spring `Page` 직접 직렬화는 포맷 불안정이라 지양). **집계 응답은 예외** — 호가창은 `depth`로 자르고 페이징하지 않는다
- **정렬**: `sort=field,(asc|desc)`(다중 가능). 엔드포인트별 **허용 필드 화이트리스트**만(임의 필드 정렬 금지 — 보안·성능)
- **주문 가격 의미**: 주문 제출은 **동기** — 응답은 "체결됨"이 아니라 "접수됨 + 즉시 체결분". 잔량은 `OPEN`으로 호가창에 남음
- **체결가**: `limitPrice`는 **한도**(매수=최대지불, 매도=최소수취)이고 실제 체결가는 **maker 가격**(호가창에 먼저 있던 주문의 가격). 매수 한도 102가 매도 한도 100과 만나면 **100**에 체결되고 차액은 taker 이득 → `fills[].price`가 `limitPrice`와 다를 수 있고, 원소마다 다를 수도 있다. 설계 스펙 D4
- **`avgFillPrice`는 표시용**: `Σ(체결가×수량) ÷ Σ수량`을 HALF_UP 정수 반올림(매수·매도 동일). 반올림이 손실적이라 **`avgFillPrice × filledQuantity`로 체결금액을 계산하면 안 된다**(101×7 = 707 ≠ 704) — 정확한 금액은 `fills` 합산
- **현금 잔고의 표준 용어는 `balance`** — `cash`를 쓰지 않는다. 원장이 `balanceAfter`로 부르고(진실의 출처, ADR-0006) 잔고는 거기서 재계산되는 파생값이라 원본의 이름을 따른다
- **잔고·수량은 총액/가용/예약 3종**: 에스크로 예약분이 있어 총액만으론 주문 가능액을 알 수 없다. `balance = availableBalance + reservedBalance`, `quantity = availableQuantity + reservedQuantity`. 예약분은 **저장값이 아니라 미체결 주문에서 유도**. `reservedBalance`의 정의는 **각 미체결 매수 주문의 최대 필요 현금의 합** — 계산식이 아니라 의미다(정산 정책이 바뀌어도 계약은 그대로). 새 주문의 최대 필요 현금이 `availableBalance`를 넘으면 `INSUFFICIENT_BALANCE`

## 엔드포인트

| Method | Path | 인증 | 설명 |
|--------|------|:---:|------|
| POST | `/auth/signup` | – | 회원가입 |
| POST | `/auth/login` | – | 로그인 → Bearer 토큰 |
| POST | `/auth/logout` | ✔ | 토큰 무효화 |
| GET | `/me` | ✔ | 내 정보 + 잔고(총액·가용·예약) |
| GET | `/players` | – | 마켓 시세 목록 (`teamId,position,sort,page,size`) |
| GET | `/players/{id}` | – | 시세 상세 (lastPrice·bestBid·bestAsk·tradable·tradableReason) |
| GET | `/players/{id}/orderbook` | – | 호가창 — 가격별 잔량 집계(`depth`). 개별 주문·주문자 미노출 |
| POST | `/orders` | ✔ | 지정가 주문(동기, +즉시 체결분) |
| GET | `/orders` | ✔ | 내 주문 목록(`status` 필터) |
| GET | `/orders/{id}` | ✔ | 주문 단건 상태 |
| DELETE | `/orders/{id}` | ✔ | 주문 취소(잔량·예약 반환) |
| GET | `/me/portfolio` | ✔ | 현금 + 보유 + 평가액 |
| GET | `/me/ledger` | ✔ | 통합 원장(체결·배당·입금) |

> `/me/trades` 없음 — 원장에 흡수(`/me/ledger?type=TRADE_BUY,TRADE_SELL`). 단일 trade 조회 없음(POST 응답+목록으로 충분).

## 핵심 응답 형태

- **주문**: `{orderId, status(OPEN|PARTIALLY_FILLED|FILLED|CANCELLED), quantity, filledQuantity, remainingQuantity, limitPrice, fills:[{price,quantity}], avgFillPrice}`
- **포트폴리오**: `{balance, availableBalance, reservedBalance, holdings:[{playerId,name,quantity,availableQuantity,reservedQuantity,avgCost,lastPrice,marketValue}], holdingsValue, totalValue}` — totalValue는 **지시적 평가**(실현가 아님)
- **호가창**: `{playerId, bids:[{price,quantity}], asks:[{price,quantity}]}` — bids는 가격 **내림차순**, asks는 **오름차순**(둘 다 최우선가부터)
- **원장 엔트리(통합)**: 공통 봉투 `{id, timestamp, type, amount(현금 델타), balanceAfter}` + `detail`(oneOf)
  - `TRADE_BUY`/`TRADE_SELL` → `{playerId,name,quantity,price,fee}`
  - `DIVIDEND` → `{playerId,name,matchLabel}`
  - `DEPOSIT` → `{reason: SIGNUP_BONUS}`

## 에러 모델

응답: `{code, message, timestamp, path}` + HTTP 상태.

| code | HTTP | 발생 |
|------|:---:|------|
| `VALIDATION_ERROR` | 400 | 요청 검증 실패 |
| `INVALID_CREDENTIALS` | 401 | 로그인 실패 |
| `UNAUTHORIZED` | 401 | 토큰 없음/무효 |
| `NOT_FOUND` | 404 | 리소스 없음 |
| `USERNAME_TAKEN` | 409 | 아이디 중복 |
| `INSUFFICIENT_BALANCE` | 409 | 포인트 부족 |
| `INSUFFICIENT_SHARES` | 409 | 주식 부족 |
| `HOLDING_CAP_EXCEEDED` | 409 | 선수별 보유 상한 초과 |
| `ORDER_NOT_CANCELLABLE` | 409 | 이미 체결/취소됨 |
| `MARKET_CLOSED` | 409 | 거래정지 선수에 주문 — `tradable=false`면 사유 무관(`HALTED_DELISTING`·`DELISTED`·`SUSPENDED` 모두). soft delete라 404 아님 |
| `RATE_LIMITED` | 429 | 주문 생성·취소 요청 한도 초과 |

> STP(자전거래 방지, Cancel Taker)는 **에러가 아니라** taker 잔량을 취소해 정상 응답 — 응답의 `status`/`remainingQuantity`로 드러남.
