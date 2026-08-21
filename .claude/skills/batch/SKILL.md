---
name: batch
description: Use when selecting prioritized GitHub Issues, reporting active batch progress, freezing implementation, or completing a parallel implementation-learning batch
argument-hint: "[status|sync|start #N...|freeze|stabilize|resume|complete] (앞에 --dry-run 가능)"
disable-model-invocation: true
allowed-tools: Bash(gh api:*) Bash(gh issue list:*) Bash(gh issue view:*) Bash(gh pr list:*) Bash(gh pr view:*) Bash(gh pr checks:*) Bash(git status:*) Bash(git log:*) Bash(git rev-parse:*) Bash(git branch:*) Bash(git fetch:*) Bash(git ls-remote:*) Bash(git worktree:*)
---

# 병렬 구현·학습 배치 관리

GitHub Issue 중 오늘 진행할 범위를 추천하고, 사용자가 확정한 배치의 구현·학습·발견 상태를
관리한다.

**핵심 원칙**

- 배치는 하나만 활성화한다.
- 구현 레인은 하나이며 일반 구현 PR은 최대 3개다.
- 추천은 읽기 전용이고, 사용자의 명시적 승인 뒤에만 배치를 초기화한다.
- 미학습 PR은 다음 구현을 막지 않지만 열린 구현 PR과 `BLOCKING`은 막는다.
- Claude Code와 Codex는 `REVIEW-QUEUE.md`의 자기 절만 수정한다.

---

## ① 명령 판별

| 입력 | 동작 |
|---|---|
| 없음 또는 `status` | 현재 상태 보고. 활성 배치가 없으면 후보 추천. **쓰기 없음** |
| `sync` | GitHub와 Claude Code 소유 큐 절을 명시적으로 동기화 |
| `start #N...` | 번호 목록을 사용자가 승인한 선택으로 보고 시작 조건 확인 후 초기화 |
| `freeze` | 선택 범위 완료·PR 상한·사용자 요청을 근거로 새 구현 차단 |
| `stabilize` | 배치 최종 리뷰가 확정한 수정 작업만 허용 |
| `resume` | 해소된 정지 사유에 따라 `ACTIVE` 또는 `STABILIZING` 복귀 여부 확인 |
| `complete` | 학습·리뷰·수정·최종 build가 끝난 배치를 완료 이력으로 이동 |
| 앞에 `--dry-run` | 모든 판단을 보고하되 파일·GitHub 상태 변경 없음 |

`start`가 아닌 추천·상태 조회에서 Issue를 선택하거나 큐를 초기화하지 않는다. “오늘 무엇부터
할지 알려줘”는 보고 요청이지 시작 승인으로 해석하지 않는다.

---

## ② 정본과 큐 소유권

구현 상태의 정본은 GitHub Issue·PR·merge SHA다.
`~/Dev/kleague-learning/REVIEW-QUEUE.md`는 배치·학습 장부다.

### 필수 스키마

```markdown
<!-- workflow-schema: batch-v1 -->

## 현재 배치
- 상태:
- 시작 SHA:
- PR 상한: 3
- 선택한 Issue:
- 진행 중 구현: 없음
- 일시 정지 구현: 없음

## 구현 산출물
<!-- Claude Code가 관리 -->
| Issue | PR | Merge SHA |

## 학습 상태
<!-- Codex가 관리 -->
| PR | 상태 | merge-base SHA | head SHA | 학습 노트 |

## 발견 사항
<!-- Codex가 관리 -->
### BLOCKING
### STABILIZATION
### FOLLOW_UP

## 완료한 배치
```

- 스키마 마커가 없거나 버전이 다르면 추측하지 않고 중단한다.
- Claude Code는 `현재 배치`, `구현 산출물`, `완료한 배치`만 수정한다.
- Codex는 `학습 상태`, `발견 사항`만 수정한다.
- 수정 직전에 파일을 다시 읽고 자기 절만 작은 패치로 바꾼다.
- 전체 파일 재작성, 자동 정렬, 표 재포맷을 하지 않는다.
- `BLOCKING` 절에 실제 항목이 있으면 상태 필드와 관계없이 실효 상태는 `FROZEN`이다.

모든 큐 쓰기는 아래 잠금을 먼저 얻는다.

```bash
mkdir ~/Dev/kleague-learning/.review-queue.lock
mkdir ~/Dev/kleague-learning/.review-queue.lock/<tool-purpose-issue-ownerToken-UTC>
```

`mkdir` 실패 시 기다리거나 기존 잠금을 지우지 않고 중단한다. 성공하면 큐를 다시 읽고 자기 절만
수정·검증한 뒤 소유자 하위 디렉터리와 `.review-queue.lock`을 그 순서로 `rmdir`한다. owner token은
`uuidgen`, UTC는 잠금 획득 시각을 사용한다. 오류로 남은 잠금은 자동 삭제하지 않는다. 사용자가
하위 디렉터리의 도구·작업·Issue·token·시각과 활성 세션을 대조해 소유 세션 종료를 명시적으로
확인한 경우에만 수동 해제한다. `--dry-run`과 `status`는 쓰지 않으므로 잠금을 만들지 않는다.
부모 생성 뒤 소유자 하위 디렉터리 생성이 실패하면 자기 빈 부모만 `rmdir`하고 중단한다.

큐가 손실되면 GitHub 이력과 학습 노트로 현재 상태를 복원한다. Batch ID를 PR에 추가하지 않는다.

### 전환 버전

모든 배치 시작 전 아래가 모두 참이어야 한다.

- `REVIEW-QUEUE.md`, `CLAUDE.local.md`, `~/.agents/skills/learn/SKILL.md`에
  `<!-- workflow-schema: batch-v1 -->`가 있다.
- 개인 `/learn`에 `.review-queue.lock`, `PENDING`, `LEARNING`, `DELTA_REQUIRED`,
  `FINALIZED`, `ABANDONED` 실행 규칙이 실제로 들어 있다.
- 기존 `~/.claude/skills/learn/SKILL.md`가 없다.

하나라도 다르면 저장소와 개인 워크플로의 부분 전환으로 보고 중단한다. `status`는 이 차이를
읽기 전용으로 보고할 수 있지만 `start`는 큐를 수정하지 않는다.

마커만 보지 않는다. 개인 `/learn` 본문에서 위 잠금 경로와 여섯 상태 토큰을 각각 검색하며 하나라도
없으면 호환되지 않은 구현으로 판정한다.

---

## ③ 상태 보고와 후보 추천

먼저 다음을 읽기 전용으로 대조한다.

1. 큐의 현재 배치·구현 산출물·학습 상태·발견 사항
2. 열린 PR과 최근 merge PR
3. 열린 Issue의 milestone·priority·status·area 라벨
4. Issue 본문의 선행 Issue·관련 계약·완료 조건

### 활성 배치가 있으면

다음을 한 번에 보고한다.

- 상태와 실효 상태
- 선택한 Issue별 구현·PR·merge 상태
- 열린 구현 PR
- 학습 상태와 `FINALIZED`되지 않은 PR
- `BLOCKING`, `STABILIZATION`, `FOLLOW_UP`
- 일반 구현 PR 사용 슬롯과 남은 슬롯
- `진행 중 구현` 선점과 GitHub branch/worktree·PR의 일치 여부
- `일시 정지 구현`과 recovery 상태
- 지금 허용되는 다음 행동

학습 미완료만 남고 직전 구현 PR이 merge됐다면 다음 선택 Issue 구현을 허용한다고 보고한다.
Ready/Draft 여부와 관계없이 구현 PR이 열려 있으면 다음 구현을 허용하지 않는다.
새 Ready PR이 생겼을 때 다른 PR 학습이 진행 중이면 새 PR은 `PENDING`으로 두며 학습 작업을
동시에 진행하지 않는다.

Codex는 공용 큐 잠금 아래 학습 상태를 다시 읽고 `LEARNING` 행이 하나도 없을 때만 가장 오래된
`PENDING` 한 행을 `LEARNING`으로 정확히 교체한다. 편집 충돌이 나거나 다른 `LEARNING`이 생기면
전이하지 않는다.

`status`는 Merge SHA를 채우거나 선점을 해제하거나 상태를 `FROZEN`으로 바꾸지 않는다. 실제
상태와 큐의 차이 및 필요한 `sync`만 보고한다.

### 활성 배치가 없으면

`MVP` milestone과 `status:ready` Issue를 기본 후보로 삼는다. 아래를 모두 만족해야 Ready다.

- 목표·범위·Non-goals·검증 가능한 완료 조건이 있다.
- 근거 계약이 연결되어 있다.
- 선행 Issue가 merge됐거나 선행 작업이 없다.
- 해결되지 않은 고위험 결정이 없다.
- 하나의 PR과 한 학습 작업으로 검토 가능한 크기다.
- 구현에 필수인 제품 정책이 빠져 있지 않다.

`status:blocked`이거나 선행 작업이 남은 Issue는 후보에서 제외하고 이유를 적는다.
우선순위는 P0 → P1 → P2, 선행 관계, 같은 제품 영역 순으로 비교한다. 1~3개를 추천하되 3개를
채우기 위해 무관한 Issue를 넣지 않는다.

여러 Issue를 한 PR로 묶으려면 하나만 구현해서는 다른 완료 조건을 충족할 수 없고, 같은 코드
경계·검증 명령을 공유하며, 함께 되돌려야 하고, 한 학습 작업에서 이해할 수 있어야 한다.

열린 실행 가능 Issue가 12개 이상이거나 그 전에 상태·의존성 파악이 불편하면 GitHub Projects
도입 후보라고 보고한다. 자동 생성하지 않는다.

---

## ④ 배치 시작

`start #N...`은 Issue 번호를 명시한 사용자 승인이다. 그 외 표현은 추천으로만 처리한다.

시작 전에 모두 확인한다.

1. 활성 배치가 없다.
2. 선택한 Issue가 1~3개다.
3. 각 Issue가 Ready 조건을 만족한다.
4. 열린 구현 PR이 없다.
5. 이전 배치의 `BLOCKING`과 미완료 Stabilization이 없다.
6. 첫 새 배치라면 기존 PR #12 학습 부채가 해소됐다.
7. 전환 버전 검사가 통과한다.
8. `main` Ruleset이 PR 경유, 필수 `build`, force push 차단을 강제한다.

하나라도 실패하면 큐를 바꾸지 않는다.

Ruleset은 모든 활성 규칙을 페이지 끝까지 읽어 확인한다.

```bash
set -o pipefail
gh api --paginate --slurp 'repos/{owner}/{repo}/rules/branches/main?per_page=100' |
  jq -e 'add |
    any(.type == "pull_request") and
    any(.type == "required_status_checks" and
        any(.parameters.required_status_checks[]?; .context == "build")) and
    any(.type == "non_fast_forward")'
```

API 오류, `jq` 오류, `false`는 모두 시작 거부다.

승인 시점에 다음을 실행한다.

```bash
git fetch origin main
git rev-parse origin/main
```

fetch가 끝난 뒤 큐를 다시 읽고 활성 배치 없음, `진행 중 구현`·`일시 정지 구현` 없음, 실효
`FROZEN`·`BLOCKING` 없음, 미완료 Stabilization 없음, 전환 버전 일치를 재검증한다. 하나라도
달라졌으면 쓰지 않고 새 상태를 보고한다.

초기화 직전 공용 잠금을 얻은 뒤 같은 조건을 다시 확인하고, 통과한 경우에만 큐를 수정한다.

그 SHA를 `시작 SHA`로 기록하고 상태를 `ACTIVE`, PR 상한을 `3`, 승인된 Issue 목록을
`선택한 Issue`에 기록한다. `진행 중 구현`은 `없음`, `구현 산출물`은 빈 표로 시작한다. Codex
소유 절은 수정하지 않는다. `일시 정지 구현`도 `없음`으로 시작한다.

---

## ⑤ ACTIVE 상태와 구현 게이트

상태를 볼 때마다 GitHub와 큐를 대조한다.

- `status`는 merge된 구현 PR의 비어 있는 Merge SHA와 stale 선점을 보고만 한다.
- `sync`는 merge SHA를 채우고, Ready PR 생성 뒤 남은 선점처럼 열린 PR이 구현 레인을 대신
  보호하는 경우만 자동 정리한다. PR이 없는 선점은 branch/worktree 존재 여부와 관계없이 자동
  해제하지 않는다. 해당 구현 세션이 종료됐다고 사용자가 명시적으로 확인한 뒤에만 정리한다.
- 닫혔지만 merge되지 않은 PR은 보고하고 Codex의 `ABANDONED` 처리와 사용자의 선점 해제 확인을
  기다린다.
- 일반 구현은 Issue 칸이 `#N`인 행만 센다.
- `#N (RECOVERY)`, `#N (STABILIZATION)` 행은 3개 상한에서 제외한다.

다음 일반 구현은 아래를 모두 만족할 때만 허용한다.

1. 상태와 실효 상태가 `ACTIVE`다.
2. 직전 구현 PR이 merge되어 열린 구현 PR이 없다.
3. 일반 구현 PR이 3개 미만이다.
4. 대상 Issue가 현재 배치에 선택되어 있다.
5. `BLOCKING`이 없다.
6. `진행 중 구현`이 `없음`이다.
7. `일시 정지 구현`이 `없음`이다.

| 상황 | 판정 |
|---|---|
| 직전 PR merge + 학습 `PENDING/LEARNING/DELTA_REQUIRED` | 다른 조건을 만족하면 다음 구현 허용 |
| 구현 PR OPEN | 다음 구현 거부 |
| `BLOCKING` 존재 | 일반 구현 거부, 지정 recovery만 허용 |
| 선택 Issue 완료 또는 일반 PR 3개 도달 | `FROZEN` 전환 |

사용자가 특정 PR에서 선택적으로 멘토 리뷰를 기다리면 PR이 열려 있는 동안 다음 구현도 기다린다.

`/issue-implement`는 worktree 생성 전에 `진행 중 구현: 없음` 한 줄을
`#N / <mode> / <branch> / <base SHA> / <owner token> / <worktree>`로 정확히 교체한다. 생성한
owner token을 가진 세션만 이를 자기 선점으로 취급하며, 같은 Issue 번호만으로 기존 선점을
이어받지 않는다. worktree 생성 전 값은 `PENDING`이고 생성 직후 실제 절대 경로로 갱신한다.
Ready PR 생성 시 구현 산출물 행 추가와 선점 해제를 같은 작은 패치에서 수행한다.

---

## ⑥ BLOCKING과 recovery

`BLOCKING`이 보이면 `status`는 실효 `FROZEN`을 보고하고, 명시적 `sync`만 Claude Code 소유의
상태 필드를 `FROZEN`으로 동기화한다. 다른 명령은 상태 drift를 보고하고 필요한 `sync` 전까지
새 전이를 수행하지 않는다. Codex 소유 발견 항목은 고치거나 지우지 않는다.

merge 전에는 현재 선점 Issue나 연결된 OPEN 구현·Stabilization PR이 자기 `BLOCKING`을
해소하는 범위에서만 기존 branch/worktree를 재개할 수 있다. 새 구현 슬롯을 열지 않는다.

- 원 Issue를 다시 연다.
- 새 일반 구현을 시작하지 않는다.
- 다음 Issue의 미커밋 worktree가 있으면 공용 잠금 아래 `진행 중 구현`의 정확한 NORMAL claim을
  빈 `일시 정지 구현`으로 옮긴다. 그 worktree는 commit·stash·reset·삭제하지 않는다.
- WIP가 없으면 `진행 중 구현`과 `일시 정지 구현`이 모두 비어 있는지 확인한다.
- 같은 잠금에서 새 recovery claim만 `진행 중 구현`을 차지한다. mode는 `RECOVERY`이며 별도 owner
  token과 최신 `origin/main` base SHA를 사용한다. WIP 이동과 recovery 선점은 한 패치다.
- 새로 fetch한 `origin/main`의 별도 recovery worktree에서 원 Issue 복구만 진행한다.
- recovery PR은 일반 PR 상한을 소비하지 않는다.

post-merge recovery가 merge되고 Codex가 `BLOCKING`을 해소한 뒤 `resume`을 평가한다. 열린
recovery PR이 없고 기존 WIP를 새 기준에서 재검토했으면, 공용 잠금 아래 `일시 정지 구현`을
`진행 중 구현`으로 되돌리고 `ACTIVE`로 복귀한다. 사용자가 WIP 폐기를 명시적으로 승인한 경우에만
정지 claim을 지운다. 정지 WIP가 없고 선택 Issue가 남았으면 `ACTIVE`, 선택 범위가 끝났으면 배치
최종화를 위해 `FROZEN`을 유지한다. 열린 Stabilization 작업이 남았으면 `STABILIZING`으로
돌아간다. 사용자가 pre-PR 고위험 정지 사유를 해소한 경우도 같은 기준을 적용한다. 어느 조건도
아니면 `FROZEN`을 유지한다.

OPEN 구현 PR의 pre-merge `BLOCKING`은 Codex가 해소를 확인하고 해당 항목을 정리했으며 PR이
같은 branch/worktree에 열려 있을 때 기존 phase로 복귀한다. OPEN Stabilization PR이면
`STABILIZING`, 일반 구현 PR 또는 PR 전 claim이면 `ACTIVE`다. `BLOCKING`이 남아 있거나 다른
Issue의 claim이면 복귀하지 않는다.

---

## ⑦ 배치 동결과 Stabilization

선택한 Issue를 모두 구현했거나 일반 PR 3개에 도달하면 `FROZEN`으로 전환한다.

동결 뒤 순서:

1. 모든 일반 구현 PR의 merge 후 최종 학습을 `FINALIZED`한다.
2. 배치 시작 SHA부터 최신 `main`까지 누적 `code-reviewer`를 수행한다.
3. 문서를 바꾼 배치에서만 `docs-auditor`를 수행한다.
4. Codex 배치 최종 리뷰가 두 보고서와 늦은 피드백을 Finding Triage한다.

`STABILIZATION` 수정이 확정됐을 때만 상태를 `STABILIZING`으로 바꾸고 별도 Issue/PR을
만든다. 범위 내 결함은 같은 Stabilization PR에서 수정·전체 재검증한다. `BLOCKING`이 남으면
merge하지 않는다. 범위 밖 개선은 `FOLLOW_UP`으로 보낸다.

수정할 것이 없으면 형식적인 Stabilization PR을 만들지 않는다.

---

## ⑧ 배치 완료

`complete` 전에 모두 확인한다.

- 현재 배치의 모든 일반·recovery·Stabilization PR이 merge 또는 정식 중단됐다.
- `진행 중 구현`이 `없음`이다.
- `일시 정지 구현`이 `없음`이다.
- merge된 모든 PR의 학습 상태가 `FINALIZED`다.
- `BLOCKING`과 미해결 `STABILIZATION`이 없다.
- 누적 리뷰와 필요한 문서 감사가 끝났다.
- PR 본문·리뷰·댓글·미해결 스레드의 늦은 피드백을 대조했다.
- 최신 `origin/main`에서 `./gradlew build`가 통과했다.

완료 조건을 실제로 확인하지 못하면 `COMPLETE`로 바꾸지 않는다.

각 PR의 본문과 상태를 읽고, top-level 댓글과 review summary는 REST 페이지 끝까지 확인한다.

```bash
set -o pipefail
gh pr view <PR번호> --json body,state,mergeCommit
gh api --paginate --slurp 'repos/{owner}/{repo}/issues/<PR번호>/comments?per_page=100' |
  jq -e 'add'
gh api --paginate --slurp 'repos/{owner}/{repo}/pulls/<PR번호>/reviews?per_page=100' |
  jq -e 'add'
```

미해결 inline 리뷰 스레드는 페이지 끝까지 GraphQL로 확인한다. 아래 판정이 `false`이거나 API·
`jq`가 실패하면 완료하지 않는다.

```bash
set -o pipefail
gh api graphql --paginate --slurp \
  -F owner='{owner}' -F name='{repo}' -F number=<PR번호> \
  -f query='query($owner:String!, $name:String!, $number:Int!, $endCursor:String) {
    repository(owner:$owner, name:$name) {
      pullRequest(number:$number) {
        reviewThreads(first:100, after:$endCursor) {
          nodes { isResolved isOutdated path }
          pageInfo { hasNextPage endCursor }
        }
      }
    }
  }' |
  jq -e '[.[].data.repository.pullRequest.reviewThreads.nodes[] |
    select(.isResolved == false)] | length == 0'
```

최종 build는 현재 checkout이 아니라 검증 전용 worktree에서 정확한 원격 SHA를 대상으로 한다.

```bash
set -euo pipefail
git fetch origin main
verify_sha=$(git rev-parse origin/main)
verify_dir=$(mktemp -d /tmp/kleague-batch-verify.XXXXXX)
cleanup_verify_worktree() {
  git worktree remove --force "$verify_dir" 2>/dev/null || true
}
trap cleanup_verify_worktree EXIT
git worktree add --detach "$verify_dir" "$verify_sha"
(cd "$verify_dir" && ./gradlew build)
git fetch origin main
test "$(git rev-parse origin/main)" = "$verify_sha"
git worktree remove --force "$verify_dir"
trap - EXIT
```

명령 하나라도 실패하거나 두 SHA가 다르면 완료 기록을 쓰지 않는다. 성공·실패와 관계없이 생성한
검증 worktree만 정리하며 기존 worktree는 건드리지 않는다.

완료 기록 직전에 공용 큐 잠금을 얻어 모든 완료 조건을 다시 읽고 원격 `main`을 직접 조회한다.

```bash
remote_main_sha=$(git ls-remote origin refs/heads/main | awk 'NR == 1 {print $1}')
test -n "$remote_main_sha"
test "$remote_main_sha" = "$verify_sha"
```

조건이 바뀌거나 원격 조회·비교가 실패하면 잠금을 해제하고 기록하지 않는다. 성공하면 검증한
`verify_sha`를 종료 SHA와 늦은 피드백 확인 기준으로 기록한다.

통과하면 시작 SHA·종료 SHA·선택 Issue·PR·학습 노트 링크를 `완료한 배치`에 한 항목으로
추가한다. 현재 배치와 구현 산출물을 비워 다음 배치를 받을 수 있게 한다. Codex 소유 절은
수정하지 않는다.

상태 보고와 다음 배치 게이트는 Codex 절 전체가 아니라 현재 `구현 산출물`의 PR에 연결된 학습·
발견 행만 활성 배치 항목으로 센다. 완료 기록에 들어간 과거 행은 새 배치를 막지 않으며, Codex가
다음 자기 절 수정 때 정리한다.

배치 완료 뒤 도착한 피드백이 원 Issue의 완료 조건 미충족이면 원 Issue recovery로 처리한다.
새 개선이나 범위 밖 요구는 `FOLLOW_UP` Issue로 분리한다.

---

## ⑨ Dry run과 출력

`--dry-run`에서는 GitHub와 큐를 읽고 아래만 보고한다.

```markdown
## 배치 상태
- 상태 / 실효 상태:
- 구현 슬롯:
- 열린 구현 PR:
- 학습 대기:
- 발견 사항:

## 후보 또는 다음 행동
- 추천 Issue:
- 제외 Issue와 이유:
- 묶음·분리 판단:
- 지금 허용되는 행동:

## 변경 예정
- 큐 변경:
- GitHub 변경:
```

Issue·PR·라벨·milestone·Ruleset·파일·브랜치·큐를 변경하지 않는다.

---

## 하지 않는 것

- 사용자 승인 전 배치 초기화
- 두 개 이상의 구현 Issue 동시 진행
- 열린 구현 PR이 있는데 다음 구현 허용
- 학습 미완료만으로 다음 구현 차단
- Codex 소유 큐 절 수정
- Batch ID를 PR 본문에 추가
- 자동 merge
- 고위험 결정을 배치 편의를 위해 우회
