# workflow

`kleague-market`의 작업 방식. 목표는 완성을 넘어 **설계와 코드를 완전히 이해하고 넘어가는 것**이다.
기억 시스템의 경계는 [[README]] 참고.

## 대원칙

- **기계적 안전은 merge 전에, 이해는 다음 배치 전에 확보한다.**
  일반 구현 PR의 학습 완료는 merge 조건이 아니지만, 배치 안의 모든 최종 diff를 학습해야 다음
  배치를 시작할 수 있다.
- **구현 레인은 하나, 학습 레인도 하나다.**
  열린 구현 PR은 하나만 두고, 구현과 Codex 학습 사이에서만 병렬화한다.
- **초안은 위임하고, 최종 판단은 위임하지 않는다.**
  Issue 범위 안의 가역적인 구현 판단은 `/issue-implement`가 내리고 PR에 근거를 남긴다.
  사용자는 PR을 검토하고 merge하며, ADR급·고위험 결정은 구현 전에 확정한다.
- **GitHub가 구현 이력의 원본이다.**
  Issue는 범위 계약, PR은 실제 구현 판단과 검증 기록이다. `REVIEW-QUEUE.md`는 배치·학습 상태를
  기록하는 로컬 장부다.
- **문서는 한 곳에만 둔다.** 같은 내용을 GitHub Issue와 `docs/`에 복제하지 않는다.

## skill 사용 지도

| 상황/신호 | skill | 하는 일 |
|---|---|---|
| 오늘 진행할 Issue 선택·배치 상태 확인 | **`/batch`** | Ready Issue 1~3개 추천, 승인 뒤 배치 초기화, 명시적 상태 동기화·동결·완료 관리 |
| 배치에 선택된 Issue 구현 | **`/issue-implement`** | 경량 Design Freeze → 테스트·구현 → 품질 게이트 → Ready PR |
| Ready PR 생성 | **Codex `/learn`** | 고정된 PR diff를 하향식으로 학습하고 학습 상태·발견 사항 갱신 |
| 목표가 흐릿함 | `brainstorming` | Issue 본문을 쓰기 전 의도·요구사항 확정 |
| 기능·버그 구현 직전 | `test-driven-development` | `/issue-implement`가 내부에서 적용 |
| 코드 작성 | `ponytail` + `karpathy-guidelines` | 최소 코드와 수술적 변경 |
| 버그·테스트 실패 | `systematic-debugging` | 추측하지 않고 근본 원인 확인 |
| 완료 주장 직전 | `verification-before-completion` | 명령을 실제 실행해 증거 확인 |
| 리뷰 피드백 수신 | `receiving-code-review` | 맹목적으로 수용하지 않고 근거 검증 |
| 브랜치 마무리 | `finishing-a-development-branch` | 통합 방식 결정 |
| 문서 변경 | `docs-auditor` | 문서 계약의 교차 정합성 검사 |
| 구현 완료·PR 전, 배치 종료 | `code-reviewer` | 코드의 D0~D9·ADR 준수 검사 |

개인 학습 스킬의 기준은 `~/.agents/skills/learn/SKILL.md`다. 저장소에는 개인 학습 노트를
커밋하지 않는다.

## 배치 모델

동시에 활성 배치는 하나만 둔다. 배치의 일반 구현 PR은 최대 3개이며, 3개는 목표가 아니라 상한이다.

| 상태 | 의미 | 새 구현 |
|---|---|---|
| `ACTIVE` | 선택한 Issue 구현과 PR 학습 진행 중 | 조건부 허용 |
| `FROZEN` | 선택 범위 완료, PR 상한 도달 또는 `BLOCKING` 발생 | 금지 |
| `STABILIZING` | 누적 리뷰 결과 수정 중 | Stabilization만 허용 |
| `COMPLETE` | 학습·수정·검증 완료 | 다음 배치 가능 |

다음 구현은 아래 조건을 모두 만족할 때만 시작한다.

- 직전 구현 PR이 merge되어 열린 구현 PR이 없다.
- 배치의 일반 구현 PR이 3개 미만이다.
- `BLOCKING`이 없다.
- 대상 Issue가 현재 배치에 선택되어 있다.
- `진행 중 구현`이 비어 있고 대상 Issue가 그 자리를 선점한다.
- `일시 정지 구현`이 비어 있다.

`진행 중 구현`은 `Issue / mode / branch / base SHA / owner token / worktree`를 기록하는 단일
구현 레인이다. 새 세션은 빈 값을 정확히 교체하고 다시 읽어 자기 선점이 유지될 때만 worktree를 만든다. 동시에 두 세션이
시도하면 먼저 선점한 하나만 진행한다. `status`는 차이만 보고하며, GitHub와 큐를 바꾸는
동기화는 명시적 `/batch sync`에서만 수행한다.

PR 없는 선점은 worktree 생성 전 정상 구간과 비정상 종료를 기계적으로 구분할 수 없으므로 자동
해제하지 않는다. 소유 구현 세션이 끝났다고 사용자가 확인한 경우에만 `/batch sync`로 정리한다.

미학습 PR이 있다는 사실만으로 다음 구현을 막지 않는다. 다만 사용자가 멘토 리뷰를 기다리기로
선택해 구현 PR을 열어 두면, 한 개의 열린 구현 PR 정책에 따라 다음 구현도 기다린다.

## 한 Issue의 흐름

1. **Issue 확인** — 목표·범위·비목표·완료 조건·근거 계약을 확인한다.
2. **구현 레인 선점** — base SHA와 branch를 정한 뒤 공용 잠금 아래 `진행 중 구현`을 선점한다.
   선점한 세션만 GitHub 댓글과 worktree를 변경한다.
3. **Design Freeze** — 구현 전에 고정해야 하는 범위와 규칙만 Issue 댓글로 남긴다. 제목은
   Goal, Acceptance Criteria, Non-goals, Invariants, Scope Decisions, Calibration,
   Implementation Sketch, Verification 여덟 개다.
4. **구현** — 새로 fetch한 최신 `origin/main`에서 Issue별 branch/worktree를
   만들고 테스트 우선으로 구현한다.
5. **PR 전 품질 게이트** — 좁은 테스트, 모듈 테스트, 전체 build, 자체 리뷰,
   `code-reviewer`를 수행한다. 문서를 바꿨을 때만 `docs-auditor`도 수행한다.
6. **Ready PR** — 실제 결함을 수정·재검증하고 알려진 `BLOCKING`이 없을 때 Draft가 아닌 PR을
   만든다. 최종 큐 확인부터 PR 생성·산출물 기록까지 공용 잠금으로 직렬화한다. PR 본문에는
   Batch ID를 넣지 않는다.
7. **학습과 리뷰** — Ready PR은 merge를 기다리지 않고 즉시 학습 후보가 된다. Codex 학습 레인이
   비어 있으면 merge-base/head SHA의 diff를 학습하고, 다른 PR을 학습 중이면 `PENDING`으로 둔다.
   학습 기준에는 두 SHA만 저장하고 diff hash·tree hash·patch-id는 저장하지 않는다. 필수
   `build`, 사용자 리뷰, 선택적인 멘토 리뷰는 병렬로 진행한다.

   Codex는 공용 큐 잠금 아래 다른 `LEARNING` 행이 없을 때만 가장 오래된 `PENDING`을
   `LEARNING`으로 정확히 교체한다. 따라서 Codex 작업은 여러 개 존재할 수 있어도 실제 학습은 한
   PR씩 진행된다.
8. **merge** — 필수 `build`가 통과하고 알려진 `BLOCKING`이 없으면 사용자가 merge한다.
9. **병렬 진행** — Claude Code는 최신 `origin/main`에서 다음 Issue를 구현하고, Codex는 이전
   PR의 최종 diff를 대조해 학습을 확정한다.

열린 PR을 수정할 때는 같은 Issue 세션·브랜치를 재개하고 품질 게이트를 다시 수행한다. 실제 PR
diff가 바뀐 경우에만 Codex가 변경분을 추가 학습한다.

구현이 장기 제품·도메인 규칙, 아키텍처 결정 또는 외부 API 계약을 바꾸면 관련 스펙·ADR·
OpenAPI를 같은 PR에서 갱신한다. 문서를 실제로 바꿨을 때만 `docs-auditor`를 수행한다.

## Finding Triage

| 분류 | 기준 | 처리 |
|---|---|---|
| `BLOCKING` | 완료 조건 미충족, 공개 계약·무결성·보안·모듈·동시성 문제, 다음 Issue의 기반 결함 | 해소 전 새 구현·merge 금지 |
| `STABILIZATION` | 완료 조건은 충족하며 다음 구현 기반을 오염시키지 않는 국소 결함·테스트 누락 | 배치 종료 시 누적 수정 |
| `FOLLOW_UP` | 새 기능, 범위 밖 개선, 별도 제품 판단 | 새 Issue로 분리하고 배치 계속 |

Ready PR 전 발견은 `/issue-implement`가 분류한다. Ready PR 후 학습·사용자·멘토 리뷰 발견은
Codex가 분류한다. 배치 종료 때는 `code-reviewer`와 필요한 `docs-auditor`가 후보를 만들고,
Codex의 배치 최종 리뷰가 확정한다. 모호하거나 고위험이면 사용자가 판단한다.

merge 후 `BLOCKING`이 발견되면 원 Issue를 다시 열고 배치를 실질적으로 `FROZEN` 처리한다.
다음 Issue의 미커밋 claim은 공용 잠금 아래 `일시 정지 구현`으로 옮기고 worktree는
commit·stash·reset·삭제하지 않는다. `진행 중 구현`은 recovery가 별도 mode·owner token으로
차지한다. 새로 fetch한 `origin/main`의 별도 recovery worktree에서 복구 PR을 처리한 뒤 기존
작업을 재검토하고 정지 claim을 복원한다. 정지할 WIP가 없으면 빈 구현 레인에 recovery claim만
기록한다. 복구가 끝난 뒤 남은 선택 Issue가 있으면 `ACTIVE`, 선택 범위가 끝났으면 최종화를 위한
`FROZEN`으로 돌아간다.

merge 전 현재 Issue나 열린 PR의 `BLOCKING` 수정은 새 구현이 아니라 pre-merge remediation이다.
실효 상태가 `FROZEN`이어도 그 Issue의 기존 branch/worktree에서 해당 결함만 수정할 수 있다.
Stabilization PR도 같은 방식으로 현재 PR의 결함만 고친다. 정지 사유가 해소되면 `/batch resume`이
남은 단계에 따라 `ACTIVE` 또는 `STABILIZING`으로 복귀시킨다.

## 배치 종료

1. 선택 범위 완료 또는 일반 구현 PR 3개 도달 시 새 구현을 막고 `FROZEN`으로 전환한다.
2. 모든 일반 구현 PR의 최종 diff 학습을 `FINALIZED`한다.
3. 배치 시작 SHA부터 최신 `main`까지 누적 `code-reviewer`를 수행한다.
4. 문서 변경이 있을 때만 `docs-auditor`를 수행한다.
5. Codex가 늦게 도착한 리뷰까지 포함해 Finding Triage를 확정한다.
6. 수정할 것이 있을 때만 Stabilization Issue/PR을 만든다. 이 PR은 일반 PR 3개 상한에 넣지 않는다.
7. Stabilization PR이 있으면 최종 diff 학습·검토와 필수 `build` 뒤 사용자가 merge한다.
8. PR 본문·리뷰·댓글·미해결 스레드를 다시 읽고 발견 사항이 해소됐는지 확인한다.
9. `진행 중 구현`이 비었는지 확인한다.
10. 새로 fetch한 `origin/main`의 정확한 SHA를 별도 worktree에서 build하고, 완료 직전에 원격 SHA가
   바뀌지 않았는지 확인한 뒤 배치를 `COMPLETE`로 이동한다.

## 세션과 상태 파일

| 작업 | 세션 |
|---|---|
| 배치 선택 | 짧은 `/batch` 세션 |
| 구현 | Issue마다 새 Claude Code 세션. PR이 merge되거나 닫힐 때까지 재개 가능 |
| 학습 | Ready PR마다 새 Codex 작업. `FINALIZED` 또는 `ABANDONED`까지 유지 |
| 배치 최종 리뷰 | 개별 PR 학습과 분리된 Codex 작업 |

`~/Dev/kleague-learning/REVIEW-QUEUE.md`는 절별 소유권을 지킨다.

- Claude Code: 현재 배치, 구현 산출물, 완료한 배치
- Codex: 학습 상태, 발견 사항

두 도구는 수정 직전에 파일을 다시 읽고 자신의 절만 작은 패치로 바꾼다. `BLOCKING` 절이 비어
있지 않으면 상태 필드와 관계없이 배치의 실효 상태는 `FROZEN`이다.

큐를 쓸 때는 둘 다 `~/Dev/kleague-learning/.review-queue.lock` 디렉터리를 `mkdir`로 먼저
선점한다. 잠금 생성에 실패하면 기다리거나 지우지 않고 중단한다. 잠금을 얻은 도구는 큐를 다시
읽어 자기 절만 수정·검증한 뒤 즉시 해제한다. 잠금 안에는 도구·작업·Issue·owner token·UTC를
이름으로 가진 빈 하위 디렉터리를 두어 비정상 종료 소유자를 식별한다. 남은 잠금은 자동 삭제하지
않고 사용자가 해당 세션 종료를 확인한 뒤 복구한다.

각 배치를 시작하기 전에는 개인 `/learn`, `CLAUDE.local.md`, `REVIEW-QUEUE.md`가 모두
`batch-v1`이고 기존 Claude `/learn`이 제거됐는지 확인한다. 하나라도 맞지 않으면 부분 전환으로
보고 새 구현을 시작하지 않는다.

## 서브에이전트 사용 규칙

에이전트 정의는 `.claude/agents/`에 있고 git으로 관리한다. `docs-auditor`와
`code-reviewer`는 읽기·검증만 하며 파일을 고치지 않는다. 구현과 테스트는 메인 세션의
`/issue-implement`가 수행한다.

에이전트 보고서는 근거 인용을 직접 확인한 뒤 반영한다. 명백한 위반은 수정·재검증하고, 판단이
갈리는 항목은 PR 리뷰 포인트에 남긴다. 여러 구현 Issue를 동시에 코딩하지 않는다.

## 버그가 나면

사이클 어디서든 버그·이상 동작이 보이면 즉시 `systematic-debugging`을 적용한다. 증상만
패치하지 말고 같은 함수의 모든 호출부를 확인해 근본 원인을 한 번에 고친다.
