# kleague-market API 요약 (MVP)

기계용 계약은 [[openapi]](`openapi.yaml`). 이건 사람이 한눈에 보는 요약. 설계 근거는 [[2026-07-29-kleague-market-design]].

## 공통 규약

- **Base**: `/api`
- **가격 단위**: 정수 **minor-unit**(포인트 ×100). **money 필드는 long(int64)**(오버플로 방지), **quantity(주식 수)는 int**(고정 공급이라 작음)
- **인증**: 헤더 `Authorization: Bearer <token>`(서버측 opaque 토큰). 공개 엔드포인트는 토큰 불필요. 토큰은 **만료**되며(`expiresIn` 초) 로그아웃 시 즉시 무효화
- **식별자**: 공개 id는 전부 **내부 id**. api-sports id는 내부 매핑 전용(미노출) → 제공자 교체해도 공개 id 불변. **타입은 도메인 엔티티(선수·팀·경기) = `long(int64)`, 사용자·주문 = `uuid(string)`** — 숫자 id를 int32로 두지 않는다
- **시간**: 모든 시각 **ISO-8601 UTC**(`2026-08-05T12:34:56Z`) — 저장·직렬화 UTC 통일
- **페이징**: `page`(0-base)·`size`(≤100), 응답에 `page/size/totalElements/totalPages`. Spring Data 스타일 필드지만 **자체 DTO로 직렬화**(Spring `Page` 직접 직렬화는 포맷 불안정이라 지양)
- **정렬**: `sort=field,(asc|desc)`(다중 가능). 엔드포인트별 **허용 필드 화이트리스트**만(임의 필드 정렬 금지 — 보안·성능)
- **주문 가격 의미**: 주문 제출은 **동기** — 응답은 "체결됨"이 아니라 "접수됨 + 즉시 체결분". 잔량은 `OPEN`으로 호가창에 남음

## 엔드포인트

| Method | Path | 인증 | 설명 |
|--------|------|:---:|------|
| POST | `/auth/signup` | – | 회원가입 |
| POST | `/auth/login` | – | 로그인 → Bearer 토큰 |
| POST | `/auth/logout` | ✔ | 토큰 무효화 |
| GET | `/me` | ✔ | 내 정보 + 잔고 |
| GET | `/players` | – | 마켓 시세 목록 (`teamId,position,sort,page,size`) |
| GET | `/players/{id}` | – | 시세 상세 (lastPrice·bestBid·bestAsk·tradable·tradableReason) |
| POST | `/orders` | ✔ | 지정가 주문(동기, +즉시 체결분) |
| GET | `/orders` | ✔ | 내 주문 목록(`status` 필터) |
| GET | `/orders/{id}` | ✔ | 주문 단건 상태 |
| DELETE | `/orders/{id}` | ✔ | 주문 취소(잔량·예약 반환) |
| GET | `/me/portfolio` | ✔ | 현금 + 보유 + 평가액 |
| GET | `/me/ledger` | ✔ | 통합 원장(체결·배당·입금) |

> `/me/trades` 없음 — 원장에 흡수(`/me/ledger?type=TRADE_BUY,TRADE_SELL`). 단일 trade 조회 없음(POST 응답+목록으로 충분).

## 핵심 응답 형태

- **주문**: `{orderId, status(OPEN|PARTIALLY_FILLED|FILLED|CANCELLED), quantity, filledQuantity, remainingQuantity, limitPrice, fills:[{price,quantity}], avgFillPrice}`
- **포트폴리오**: `{cash, holdings:[{playerId,name,quantity,avgCost,lastPrice,marketValue}], holdingsValue, totalValue}` — totalValue는 **지시적 평가**(실현가 아님)
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
