---
name: issue-implement
description: Use when implementing or resuming a GitHub Issue selected in the active batch, through Design Freeze, tests, verification, review, and a Ready PR
argument-hint: "[issue-number|issue-url|feature-description] (앞에 --dry-run 가능)"
disable-model-invocation: true
allowed-tools: Bash(gh api:*) Bash(gh issue view:*) Bash(gh issue list:*) Bash(gh pr list:*) Bash(gh pr view:*) Bash(gh pr checks:*) Bash(gh pr create:*) Bash(gh pr edit:*) Bash(git status:*) Bash(git diff:*) Bash(git log:*) Bash(git branch:*) Bash(git worktree:*) Bash(git ls-remote:*) Bash(git remote show:*) Bash(git fetch:*) Bash(git rev-parse:*)
---

# Issue 기반 구현

`$ARGUMENTS`의 GitHub Issue를 범위 계약으로 삼아 Ready PR까지 구현한다. merge는 사용자의
권한이며 이 스킬은 호출하지 않는다.

**핵심 원칙**

- 새 구현은 현재 배치에 선택된 Issue 하나만 수행한다.
- 구현 레인은 하나다. 다른 구현 PR이 열려 있으면 새 Issue를 시작하지 않는다.
- 미학습 PR은 다음 구현을 막지 않지만, `BLOCKING`은 즉시 막는다.
- PR 전 품질 게이트는 테스트·전체 build·자체 리뷰·`code-reviewer`의 조합이다.
- 초안은 위임하되 최종 판단은 사용자가 한다.

이 스킬은 호출 뒤 전체 작업에 계속 적용되는 실행 규칙이다.

> frontmatter의 `allowed-tools`는 사전 승인 목록이다. PR 생성·갱신은 짧은 큐 잠금 안에서
> 끝내야 하므로 이 명시적 스킬 호출에만 사전 승인한다. merge·Issue close는 승인하지 않는다.

---

## ① 입력과 작업 종류 확정

`--dry-run`이 앞에 있으면 **⑨ Dry run**으로 간다.

### 입력 판별

| 조건 | 처리 |
|---|---|
| `^#?[0-9]+$` | Issue 번호로 조회 |
| `github.com/<owner>/<repo>/issues/<N>` | 현재 저장소와 owner/repo가 다르면 중단 |
| 그 외 자연어 | 유사 Issue를 찾고, 없으면 Issue 초안을 승인받아 생성한 뒤 종료 |

자연어 요구사항으로 새 Issue를 만들더라도 즉시 구현하지 않는다. `/batch`가 그 Issue를 현재
배치에 선택해야 구현할 수 있다.

### 대상 Issue의 기존 작업을 먼저 찾는다

새 작업 게이트보다 재개 판정을 먼저 한다.

1. `gh issue view <N> --json number,title,body,state,labels,closedByPullRequestsReferences`
2. `gh pr list --state all --limit 1000 --search "<N>" --json number,state,isDraft,headRefName,closingIssuesReferences`
3. `closedByPullRequestsReferences`를 우선 사용하고, 숫자 검색 결과는
   `closingIssuesReferences` 또는 PR 본문의 `Closes #N`으로 다시 확인한다.
4. `git branch -a`와 `git worktree list`에서 Issue 브랜치를 확인한다.

판정:

- **OPEN PR**: 같은 Issue 세션·브랜치를 재개한다. 새 배치 슬롯을 쓰지 않는다.
- **MERGED PR + 열린 원 Issue**: `BLOCKING` 복구로 명시된 경우만 recovery 작업으로 처리한다.
- **MERGED PR + 복구 근거 없음**: 이미 완료된 작업으로 보고하고 중단한다.
- **기존 Issue 브랜치만 존재**: 범위와 상태를 확인한 뒤 재사용한다.
- **아무 작업도 없음**: 새 구현 게이트로 간다.

중복 브랜치와 중복 PR을 만들지 않는다.

---

## ② 배치 게이트

`~/Dev/kleague-learning/REVIEW-QUEUE.md`를 수정 직전에 다시 읽는다.

### 스키마와 소유권

- 첫 줄의 `<!-- workflow-schema: batch-v1 -->`가 없거나 다른 버전이면 추측하지 않고 중단한다.
- 이 스킬은 Claude Code 소유 절 중 `현재 배치`와 `구현 산출물`만 수정한다.
- `학습 상태`와 `발견 사항`은 Codex 소유이므로 수정하지 않는다.
- 파일 전체 재작성, 자동 정렬, 표 재포맷을 하지 않는다.

큐를 쓸 때는 `mkdir ~/Dev/kleague-learning/.review-queue.lock`에 성공한 세션만 수정한다.
실패하면 기다리거나 기존 잠금을 지우지 않고 중단한다. 성공한 세션은 잠금 안에서 큐를 다시 읽고
`claude-<purpose>-issue-<N>-<ownerToken>-<UTC>` 이름의 빈 하위 디렉터리로 소유 정보를 남긴다.
자기 절만 수정·검증한 뒤 하위 디렉터리와 잠금 디렉터리를 순서대로 `rmdir`한다. 남은 잠금은
자동 삭제하지 않는다.

`BLOCKING` 절에 항목이 있으면 상태 필드와 관계없이 배치의 실효 상태는 `FROZEN`이다.

### 새 일반 구현 허용 조건

아래를 모두 만족해야 한다.

1. 현재 배치 상태가 `ACTIVE`다.
2. 대상 Issue가 `선택한 Issue`에 있다.
3. `BLOCKING` 항목이 없다.
4. `구현 산출물`의 일반 구현 PR이 3개 미만이다.
5. 다른 구현 PR이 열려 있지 않다.
6. 새 구현이면 `진행 중 구현`과 `일시 정지 구현`이 모두 `없음`이다.

열린 PR은 Draft 여부와 관계없이 센다.

```bash
set -o pipefail
gh api --paginate --slurp 'repos/{owner}/{repo}/pulls?state=open&per_page=100' |
  jq -e 'add | map({number, draft, title, body, head: .head.ref, base: .base.ref})'
```

브랜치, `Closes #N`, 연결된 Issue를 대조해 기능·버그·recovery·Stabilization PR을 식별한다.
대상 Issue 자신의 OPEN PR 재개는 5번의 예외다.

`PENDING`, `LEARNING`, `SNAPSHOT_LEARNED`, `DELTA_REQUIRED` 같은 학습 상태는 새 구현
게이트가 아니다. 직전 구현 PR이 merge되어 열려 있는 구현 PR이 없으면 다음 선택 Issue를 시작할
수 있다.

| 직전 PR·학습 상태 | 새 구현 판정 |
|---|---|
| PR merge + 학습 미완료 + `BLOCKING` 없음 | 다른 게이트를 만족하면 **허용** |
| Ready/Draft와 관계없이 구현 PR OPEN | **거부** |
| 학습 행 누락 + 구현 PR OPEN | **거부** |
| `BLOCKING` 존재 | 일반 구현 **거부**; 지정된 recovery만 허용 |

이전 스키마의 `대기 중` 항목이나 “학습 완료가 다음 구현 조건” 규칙을 적용하지 않는다.

### 예외 작업

- **Recovery**: 해당 Issue를 가리키는 `BLOCKING`을 해소하는 작업만 허용한다.
- **Stabilization**: 현재 상태가 `STABILIZING`이고 배치 최종 리뷰가 확정한 Issue만 허용한다.
- **Pre-merge remediation**: 현재 선점 Issue 또는 연결된 OPEN 구현·Stabilization PR이 자기
  `BLOCKING`을 해소하는 작업만 허용한다.

Recovery와 Stabilization은 일반 구현 PR 3개 상한을 소비하지 않는다. 다른 새 기능을 섞지 않는다.

게이트가 닫혔다면 해당 상태와 근거를 보고하고 중단한다. 학습 미완료만을 이유로 중단하지 않는다.

---

## ③ 구현 레인 선점

새 구현은 먼저 기본 브랜치를 확인하고 fetch해 base SHA를 고정하되 branch/worktree와 Issue
댓글은 아직 만들지 않는다.

```bash
git remote show origin | sed -n 's/.*HEAD branch: //p'
git fetch origin <기본브랜치>
git rev-parse origin/<기본브랜치>
```

모든 mode는 공용 잠금 아래 활성 claim을 잡는다. NORMAL·STABILIZATION은 큐의 정확한
`- 진행 중 구현: 없음` 한 줄만 다음 값으로 교체한다. RECOVERY는 아래 경우만 허용한다.

```text
- 진행 중 구현: #N / <NORMAL|RECOVERY|STABILIZATION> / <branch> / <base SHA> /
  <새 owner token> / PENDING
```

RECOVERY이고 `진행 중 구현`과 `일시 정지 구현`이 모두 `없음`이면 정확한 활성 빈 줄을 RECOVERY
claim으로 교체한다. 다른 NORMAL claim이 이미 있고 `일시 정지 구현`이 비어 있으면, 같은 잠금과
한 패치 안에서 그 정확한 NORMAL claim을 `일시 정지 구현`으로 옮기고 `진행 중 구현`에 RECOVERY
claim을 기록한다. 그 밖에 `일시 정지 구현`이 이미 차 있거나 활성 claim이 NORMAL이 아니면
중단한다.

owner token은 `uuidgen`으로 새로 만들고 현재 세션이 보관한다. 잠금을 얻기 전 GitHub 게이트를
확인하고, 잠금 안에서는 큐를 다시 읽어 정확한 이전 값만 교체한다. 편집 충돌이 나거나 다시 읽은
Issue·mode·branch·base SHA·token이 자기 값과 다르면 파일과 GitHub를 더 바꾸지 않고 중단한다.
선점에 성공한 세션만 Design Freeze 댓글과 worktree 생성을 진행한다.

같은 Issue 번호만으로 기존 선점을 자기 것으로 취급하지 않는다. 기존 claim 재개는 사용자가
해당 세션 재개를 명시하고, 현재 디렉터리가 claim의 실제 worktree 경로와 같을 때만 허용한다.
원 세션이 종료됐다고 사용자가 확인한 새 세션은 공용 잠금 아래 기존 claim의 Issue·mode·branch·
base·worktree를 유지하고 owner token만 새 값으로 교체한 뒤 재개한다. 기존 OPEN PR을 재개할 때도
PR head branch와 현재 worktree가 일치해야 한다.

---

## ④ 경량 Design Freeze

Issue 본문과 근거 문서(`docs/superpowers/specs/`, `docs/adr/`, `docs/api/`)를 읽고 아래
형식의 Issue 댓글을 남긴다.

```markdown
## 구현 계약 및 Design Freeze

### Goal

### Acceptance Criteria

### Non-goals

### Invariants

### Scope Decisions

### Calibration

### Implementation Sketch

### Verification
```

Design Freeze에는 구현 전에 고정해야 하는 범위와 규칙만 남긴다.

- 핵심 Scope Decision은 3~5개로 제한한다.
- 구현 방향은 책임 수준으로 쓴다.
- 정확한 파일·클래스·포트 이름, 메서드 시그니처를 고정하지 않는다.
- 세부 구현 판단, 기각한 대안, 실제 구조와 Freeze의 차이는 PR에 기록한다.
- 이 Issue만을 위한 별도 `docs/` 구현 계획 문서를 만들지 않는다.

기존 Design Freeze 댓글이 있으면 현재 계정이 작성한 최신 댓글이고 후속 논의가 없어 안전할 때
편집한다. 그렇지 않으면 삭제하지 않고 다음 제목의 댓글 하나로 대체한다.

```markdown
## 구현 계약 및 Design Freeze v2

> 이 댓글이 이전 Design Freeze 댓글을 대체한다.
```

중복 대체 댓글을 만들지 않는다. 댓글을 기록한 뒤 형식적인 승인을 기다리지 않고 구현한다.

### Freeze를 다시 여는 조건

현재 설계로 완료 조건을 충족할 수 없거나 계약 충돌, 실제 테스트 결함, 데이터 무결성·동시성·
보안·트랜잭션 경계 문제가 발견됐을 때만 다시 연다. 더 예쁜 구조나 미래 확장 가능성은 근거가
아니다.

실제 범위 결정이 바뀌면 Issue에 `Design Change` 댓글로 변경 내용·근거·영향·검증을 남긴다.

---

## ⑤ worktree

### 일반 구현

브랜치는 `feature/issue-<N>-<slug>` 또는 `fix/issue-<N>-<slug>`다. 최신
`origin/<기본 브랜치>`에서 만들고 이전 기능 브랜치 위에 쌓지 않는다.

```bash
git worktree list
mkdir -p ../football-trading-worktrees
git worktree add -b <새-branch> <worktree-path> <선점한-base-SHA>
git worktree add <worktree-path> <이미-존재하는-미연결-branch>
git -C <worktree-path> rev-parse --show-toplevel
git -C <worktree-path> branch --show-current
git -C <worktree-path> rev-parse HEAD
```

Claude Code에 native worktree 도구가 있으면 먼저 사용하고, 위 명령은 fallback이다. 이미 branch가
연결된 worktree가 있으면 그 경로를 재사용하고 새 명령을 실행하지 않는다. branch만 있고 연결된
worktree가 없으면 두 번째 명령, branch도 없으면 첫 번째 명령을 사용한다. 새 branch의 HEAD는
선점한 base SHA와 같아야 하며, 기존 branch는 보존된 commit을 포함한 자기 HEAD를 사용한다.

생성·재사용 직후 실제 절대 worktree 경로와 branch를 검증하고, 공용 잠금 아래 자기 token의
`PENDING`만 그 경로로 교체한다. 이후 파일 수정과 명령은 그 worktree에서만 수행한다.

### merge 후 BLOCKING 복구

다음 Issue의 미커밋 작업이 있다면 현재 worktree를 commit·stash·reset·삭제하지 않고 그대로
일시 정지한다. ③에서 옮긴 NORMAL claim은 그대로 보존하고, 최신 `origin/main`의 원 Issue를 위한
RECOVERY claim으로만 진행한다. recovery는 별도 owner token, `fix/issue-<N>-recovery` branch,
worktree를 사용한다.

복구 PR도 일반 PR과 같은 품질 게이트, Ready PR, 필수 `build`, 사용자 merge를 거친다. merge
후 기존 WIP가 새 기준에서 유효한지 다시 검토하고 필요하면 수정하거나 폐기한다.

무관한 미커밋 변경을 되돌리거나 삭제하지 않는다. `git add .`로 무분별하게 stage하지 않는다.

---

## ⑥ 테스트 우선 구현

테스트를 먼저 쓰고 기대한 이유로 실패하는지 확인한 뒤 최소 구현으로 통과시킨다. 작은 수직
단위로 완성하며 레이어별 뼈대만 남기지 않는다.

시작 전에 다음을 읽는다.

- `CLAUDE.md`의 구현 규칙
- `docs/api/README.md` 공통 규약
- `.claude/agents/code-reviewer.md` 체크리스트

### 캘리브레이션

튜닝 값 미확정만으로 멈추지 않는다. Issue에 이번 입력값·임시 근거·조정 경계를 남기고 정책
객체나 설정 경계 한 곳에 둔다. 제품 기본값이 확정되지 않았으면 임의의 운영 기본값을 만들지 않는다.

### 범위 밖 발견

Issue의 Non-goals를 기준으로 판단한다. 독립 구현 가능하고 완료 조건이 구체적인 실제 후속 작업만
Issue로 만들며, 스타일 취향이나 막연한 확장 아이디어는 PR의 `Follow-up`에만 남긴다.

---

## ⑦ 품질 게이트와 Finding Triage

좁은 것부터 실행한다.

```text
1. 바꾼 테스트 클래스
2. 해당 모듈 테스트
3. ./gradlew build
4. 자체 리뷰
5. code-reviewer
6. 문서를 바꿨을 때만 docs-auditor
```

`code-reviewer`는 D0~D9·ADR 준수 검사다. 통과가 일반 결함이 없음을 뜻하지 않으므로 자체
리뷰를 생략하지 않는다.

구현 중 장기 제품·도메인 규칙, 아키텍처 결정, 외부 API 계약이 달라졌는지 판정한다. 달라졌다면
관련 스펙·ADR·OpenAPI를 같은 PR에서 갱신한다. 문서를 실제로 바꿨을 때만 `docs-auditor`를
수행한다.

보고서의 근거를 직접 확인한다.

- 실제 결함: 수정하고 좁은 테스트부터 전체 build까지 다시 실행한다.
- 판단이 갈리는 항목: PR의 `구현자가 확인받고 싶은 판단`에 남긴다.
- `FOLLOW_UP`: 현재 범위를 키우지 않는다.
- 해소되지 않은 `BLOCKING`: Ready PR을 만들지 않는다.

현재 Issue에서 바로 고칠 수 있는 `BLOCKING`은 같은 선점·branch/worktree에서 수정·재검증한다.
이는 새 구현이 아닌 pre-merge remediation이므로 실효 상태가 `FROZEN`이어도 해당 결함 해소
범위에서만 진행할 수 있다. 범위나 고위험 결정 때문에 해소할 수 없다면 `현재 배치`를
`FROZEN`으로 바꾸고 Issue 댓글에 근거를 남긴 뒤 중단한다. Codex 소유인 `발견 사항` 절은
수정하지 않는다.

실패한 테스트를 통과했다고 보고하지 않는다. 실행하지 못한 명령·오류·미검증 범위를 그대로 남긴다.

---

## ⑧ 커밋·push·Ready PR

### 커밋

stage·commit 직전에 `REVIEW-QUEUE.md`를 다시 읽고 실효 상태, `BLOCKING`, 자기 `진행 중 구현`
mode·owner token·branch·worktree 선점을 재확인한다.
다른 Issue의 `BLOCKING`이면 현재 작업을 commit하지 않고 중단한다. 현재 Issue를 가리키는
`BLOCKING`이면 그 결함을 직접 해소하는 remediation commit만 허용하고 새 기능 진도를 섞지 않는다.
자기 token과 현재 worktree가 일치하지 않으면 stage·commit하지 않는다. 기존 OPEN PR 재개는
그 PR head branch와 현재 worktree의 일치가 선점을 대신한다.

관련 파일만 stage하고 사용자의 무관한 변경을 포함하지 않는다. 테스트가 깨진 중간 상태를 최종
커밋으로 남기지 않는다. `reset --hard`, 강제 push, 공유된 커밋 재작성은 하지 않는다.

메시지 규약:

- 본문은 무엇이 바뀌었는지 불릿만 쓴다.
- 본문 문장 끝에 마침표를 붙이지 않는다.
- 커밋·PR 본문에 Claude 관련 문구를 넣지 않는다.

### push

먼저 읽기 프로브를 실행한다.

```bash
GIT_TERMINAL_PROMPT=0 git ls-remote origin HEAD
```

프로브 성공 시 현재 브랜치를 push한다. 실패·무응답이면 push를 반복하지 않고 사용자가 실행할
명령을 전달한다.

### Ready PR

- 같은 브랜치의 열린 PR이 있으면 새로 만들지 않고 본문을 갱신한다.
- `--draft` 없이 Ready PR을 만든다.
- 기본 브랜치를 조회해 `--base`로 사용한다.
- Issue 작업이면 `Closes #<N>`을 포함한다.
- Batch ID는 PR 본문에 넣지 않는다.
- merge하거나 Issue를 직접 닫지 않는다.
- CI 상태는 한 번 확인하고 pending이면 반복 polling하지 않는다.

PR 본문은 템플릿을 따르며 다음을 실제 내용으로 채운다.

- 실제 클래스·파일·포트 구조와 세부 인터페이스 선택
- Design Freeze에서 고정하지 않은 구현 판단과 근거
- 고려했다가 버린 대안
- Design Freeze와 실제 구현의 차이
- 실행한 테스트와 미검증 범위
- 사용자가 최종 판단할 리뷰 포인트
- 범위 밖 Follow-up

PR 생성 또는 갱신 직전에 공용 잠금을 얻고 `REVIEW-QUEUE.md`를 다시 읽어 `BLOCKING`, 실효 상태,
자기 mode·owner token·branch·worktree 또는 기존 OPEN PR 연결을 다시 검증한다. 다른 `BLOCKING`이나
선점 상실이 생겼으면 잠금을 해제하고 Ready PR을 만들거나 갱신하지 않는다.

검증을 통과하면 잠금을 유지한 채 PR을 생성하거나 갱신하고, 성공한 경우 Claude Code 소유인
`구현 산출물`에 Issue와 PR 번호를 추가하면서 `진행 중 구현`을 `없음`으로 바꾸는 한 번의 작은
패치를 적용한 뒤 잠금을 해제한다. RECOVERY 중 `일시 정지 구현`은 그대로 둔다. 원격 작업이나 큐
패치가 실패하면 잠금을 해제하고 같은 PR을 중복 생성하지 않는다. 일반 구현은 `#N`,
복구는 `#N (RECOVERY)`, 안정화는 `#N (STABILIZATION)`으로 Issue 칸에 표시하며 뒤의 두 종류는
PR 3개 상한에 세지 않는다. Codex 소유인 `학습 상태`를 대신 만들지 않는다.

PR은 생성됐지만 큐 패치가 실패하면 반복 생성하지 않는다. 열린 PR이 구현 레인을 막으므로
`/batch sync`가 산출물과 선점을 복구할 때까지 상태를 보고하고 중단한다.

Ready PR은 merge를 기다리지 않고 즉시 Codex 학습 후보가 된다. 학습 레인이 비어 있으면 바로
시작하고, 다른 PR을 학습 중이면 `PENDING`으로 둔다. 학습 완료와 멘토 리뷰는 일반 PR의 merge
조건이 아니며, 필수 `build` 통과와 알려진 `BLOCKING` 부재를 확인한 사용자가 merge한다.

열린 PR에 commit이 추가되면 같은 Issue 세션·브랜치에서 품질 게이트를 다시 통과하고 PR 본문을
갱신한다. 배치 슬롯은 추가하지 않는다.

---

## ⑨ Dry run

`--dry-run`은 조회와 출력만 수행한다.

```text
입력 판별
→ Issue·PR·branch/worktree 조회
→ REVIEW-QUEUE 스키마·배치 게이트 판정
→ 예상 Design Freeze
→ 예상 변경 책임과 검증 명령
→ 종료
```

Issue 생성·댓글·파일 수정·브랜치/worktree 생성·commit·push·PR 생성·큐 변경을 하지 않는다.

---

## ⑩ 고위험 정지 조건

다음은 구현 전에 멈추고 사용자의 결정을 받는다.

- 권위 있는 계약끼리 충돌
- 공개 API 호환성 변경
- DB 스키마·마이그레이션 또는 데이터 손실 가능성
- 인증·인가 정책
- 멀티모듈 경계
- 거래·트랜잭션·동시성 모델
- 새 외부 의존성 또는 주요 Java·Spring Boot·Gradle 버전
- 기존 ADR 대체
- Issue 범위로 구현할 수 없는 제품 정책 누락
- GitHub 인증·권한 실패

질문이 여러 개면 확인된 사실·필요한 결정·선택지·추천안을 한 메시지로 묶는다.

---

## 하지 않는 것

- merge 또는 Issue 직접 close
- ADR을 사용자 대신 확정
- 선택되지 않은 Issue 구현
- Issue 범위 밖 리팩터링
- 구현 세션에서 학습 노트 작성
- 기존 Issue 본문 전면 교체
- 다른 도구 소유 큐 절 수정
