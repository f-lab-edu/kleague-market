# kleague-market

K리그 선수를 자산으로 사고파는 가상 포인트 판타지 주식 거래소.
실제 경기 성적이 배당으로 반영된다. 목표는 완성이 아니라 **설계와 코드를 완전히 이해하는 것**.

## 작업 시작 전 반드시

**`docs/workflow.md`를 읽는다.** skill 사용 지도, 한 기능 사이클, 세션 분할, 서브에이전트 규칙이 거기 있다.
핵심만: **읽기(검증·리뷰)는 위임하고, 쓰기(구현·테스트)는 위임하지 않는다.**

규칙 원본은 `docs/`에 있고 **이 파일에 복사하지 않는다** — 두 곳에 있으면 한쪽만 고쳐서 썩는다.

## 진실의 출처

| 무엇 | 어디 |
|---|---|
| 제품·경제·게임 규칙 (D0~D9) | `docs/superpowers/specs/2026-07-29-kleague-market-design.md` |
| 아키텍처 결정 | `docs/adr/` — 불변, 결정에 영향 없는 오기 정정만 허용 |
| API 계약 | `docs/api/openapi.yaml`(기계) + `docs/api/README.md`(사람) — 코드와 충돌하면 **계약이 진실**. 코드를 임의로 고치지 말고 보고한다 |
| 작업 방식 | `docs/workflow.md` |

`docs/`는 Obsidian 보관함이다. 노트 간 연결은 `[[노트 이름]]` 위키 링크로 쓴다.

## 모듈 (ADR-0001)

```
app(Spring Boot) → domain(순수 자바) ← kleague-client(ACL)
```

`domain`은 Spring도 외부 API도 **모른다**. build.gradle에 Spring이 없어 컴파일 자체가 막힌다.
`kleague-client`가 api-sports DTO → 도메인 모델 번역을 전담한다.

Gradle 멀티모듈, **Java 21**. 빌드·테스트 명령은 실제로 돌릴 게 생기면 여기 추가한다.

**공통 빌드 규약은 `build-logic/src/main/groovy/kleague.java-conventions.gradle`에 둔다** (ADR-0008).
모듈 `build.gradle`엔 그 모듈 고유의 것만 — toolchain·repositories를 모듈에 다시 쓰지 않는다.

## 구현 시 자주 틀리는 규칙

- 금액은 **정수 minor-unit `long`** (`double`/`float` 금지), 주식 수량은 `int`
- 시각은 **UTC `Instant`** (`LocalDateTime` 지양)
- 공개 id는 **내부 id** — 엔티티(선수·팀·경기)는 `long`, 사용자·주문은 `uuid`. api-sports id 미노출
- 원장·이벤트 로그는 **append-only** — 과거 이벤트를 UPDATE/DELETE 하지 않는다 (ADR-0006)
- 매칭은 **선수별 직렬화 + 단일 원자 트랜잭션**, 외부 호출은 락 밖 (ADR-0005)

전체 체크리스트는 `.claude/agents/code-reviewer.md`에 있다.

## 커밋

- **커밋 전 diff와 메시지를 사용자에게 보여주고 승인받는다** — 자동 커밋 금지
- 본문은 **"무엇이 바뀌었나" 불릿만**. 왜 하는지를 산문으로 앞에 붙이지 않는다
- 본문 문장 끝에 **마침표를 붙이지 않는다**
- **Claude 관련 문구를 넣지 않는다** (`Co-Authored-By`, `Generated with` 등)

## 이 파일을 늘리지 말 것

매 세션 자동 로드되므로 한 줄 추가는 영구 비용이다. 길어지면 아무도 제대로 안 읽는다.

추가 기준: **"이걸 안 읽은 사람이 틀린 행동을 하는가?"** + **"포인터로 대체 불가능한가?"** — 둘 다 예일 때만.

여기 넣지 않는 것: 진행 상황(→ claude-mem·git) · 설계 결정(→ `docs/adr/`·스펙) · 작업 방식 본문(→ `docs/workflow.md`)
