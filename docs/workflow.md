# workflow

`kleague-market`의 작업 방식. 목표는 완성을 넘어 **설계와 코드를 완전히 이해하고 넘어가는 것**.
기억 시스템의 경계는 [[README]] 참고.

## 대원칙

- **이해가 속도보다 우선.** 이해 안 된 채로 다음 기능으로 넘어가지 않는다.
- **서브에이전트/병렬 skill은 기본으로 쓰지 않는다.** 과정을 숨겨서 학습을 해친다.
  이해가 필요 없는 단순 반복작업에만 쓴다.
- **문서는 한 곳에만.** 같은 내용을 Notion·docs 양쪽에 독립 작성하지 않는다. Notion은 docs에서 파생.

## skill 사용 지도 (언제 → 무엇)

| 상황/신호 | skill | 하는 일 |
|-----------|-------|---------|
| "X를 만들자", 목표가 흐릿함 | `brainstorming` | 코드 전, 의도·요구사항·설계 확정 |
| 요구사항 잡힘, 다단계 작업 | `writing-plans` | 단계별 계획 문서화 (성공 기준 명시) |
| 기능/버그 구현 직전 | `test-driven-development` | 테스트 먼저 → 최소 코드로 통과 |
| 코드 짤 때 항상 | `ponytail` + `karpathy-guidelines` | 최소 코드 / 수술적 변경·가정 명시 |
| 버그·테스트 실패·이상 동작 | `systematic-debugging` | 추측 금지, 근본 원인부터 |
| "다 됐다" 직전 | `verification-before-completion` + `/verify` | 실제 실행해 증거 확인 |
| 기능 완료·머지 전 | `requesting-code-review` / `/code-review` | 요구사항·품질 점검 |
| 리뷰 피드백 받음 | `receiving-code-review` | 맹목 수용 말고 검증 후 반영 |
| 브랜치 마무리 | `finishing-a-development-branch` | 통합 방식 결정 |
| 격리 필요 (나중) | `using-git-worktrees` | 작업 공간 분리 |

## 한 기능 사이클

1. **brainstorming** — 무엇을/왜. 목표가 흐릿하면 무조건 여기부터.
2. **writing-plans** — 요구사항이 잡히면 단계별 계획. 성공 기준(검증 방법)을 각 단계에 적는다.
3. **이해 확인** — 코드 전, 내가 설계를 설명하고 당신이 되짚는다(teach-back). 막히면 2번으로.
4. **구현** — `test-driven-development` + `ponytail`(최소 코드) + `karpathy`(가정 명시).
5. **검증** — `verification-before-completion` + `/verify`. 실제로 돌려 증거 확인. 추측 금지.
6. **리뷰** — `/code-review`. 피드백은 `receiving-code-review`로 검증 후 반영.
7. **docs 갱신 (DoD)** — 이 사이클에서 바뀐 아키텍처/결정을 `[[architecture]]`·`adr/`에 반영.
   **이걸 해야 기능 완료.** 안 하면 위키가 썩는다.
8. **마무리** — 커밋/브랜치. `finishing-a-development-branch`로 통합 결정.
   (Claude-Mem이 세션 로그를 자동 기록한다.)

## 버그가 나면

사이클 어디서든 버그·이상 동작이 보이면 즉시 `systematic-debugging`.
증상만 패치하지 말고, 같은 함수의 모든 호출부를 확인해 근본 원인을 한 번에 고친다.