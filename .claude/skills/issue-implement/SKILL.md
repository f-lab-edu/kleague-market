---
name: issue-implement
description: GitHub Issue를 범위 계약으로 삼아 조사·설계·테스트·구현·검증·문서화·Draft PR 생성까지 수행한다. 이슈 번호, 이슈 URL, 자연어 요구사항 중 하나를 받는다. merge는 하지 않는다
argument-hint: "[issue-number|issue-url|feature-description] (앞에 --dry-run 가능)"
disable-model-invocation: true
allowed-tools: Bash(gh issue view:*) Bash(gh issue list:*) Bash(gh pr list:*) Bash(gh pr view:*) Bash(git status:*) Bash(git diff:*) Bash(git log:*) Bash(git branch:*) Bash(git ls-remote:*) Bash(git remote show:*) Bash(git fetch:*)
---

# Issue 기반 구현

`$ARGUMENTS`를 범위 계약으로 삼아 Draft PR까지 만든다.

**역할 경계 한 줄:** 초안은 위임하고, **최종** 판단은 위임하지 않는다.
구현·테스트와 **가역적이고 Issue 범위 안의 설계 판단**은 여기서 결정하고 근거를 남긴다.
사람은 Draft PR에서 그 결정을 **최종 수용·수정·기각**한다. ADR급·고위험 결정은 구현 전에 사람이 확정한다.

이 스킬은 **호출된 뒤 전체 작업에 계속 적용되는 실행 규칙**이다. 1회성 안내가 아니다.

> frontmatter의 `allowed-tools`는 **화이트리스트가 아니라 사전 승인 목록**이다 — 목록에 없는 도구도 기존 권한 정책에 따라 그대로 호출된다. 여기엔 **조회 명령만** 넣었다. `git push`·`gh issue create`·`gh pr create`는 일부러 빼서 권한 프롬프트가 뜨게 했다(원격에 접촉한다는 신호).

---

## ① 입력 판별 → Issue 확정

`--dry-run`이 앞에 붙어 있으면 → **⑦ Dry run**으로 간다.

**순서가 중요하다.** 게이트를 먼저 보면 *같은 Issue를 이어서 하는 경우*까지 막힌다 — 그 Issue의 Draft PR이 바로 게이트에 걸리기 때문이다. **대상 Issue를 먼저 확정하고, 그 Issue의 작업을 먼저 찾은 다음, 남의 것으로 게이트를 건다.**

### 1단계 — 입력 판별

| 조건 | 분기 |
|---|---|
| `^#?[0-9]+$` | 이슈 번호 → 2단계 |
| `github.com/<owner>/<repo>/issues/<N>` | URL — **owner/repo가 이 저장소가 아니면 중단·보고** → 2단계 |
| 그 외 | 자연어 → **대상 Issue가 아직 없으므로 3단계(게이트)를 먼저 본 뒤** 아래 *자연어인 경우*로 |

### 2단계 — 이 Issue의 기존 작업 찾기 (재개 우선)

```
1. gh issue view <N> --json number,title,body,state
   CLOSED면 확인 요청 후 중단

2. gh pr list --state all --search "<N>" \
     --json number,state,isDraft,headRefName,closingIssuesReferences
   ※ --state 기본값이 open이라 --state all 이 없으면 MERGED를 못 찾는다
   ※ 숫자만 검색하면 오탐이 있다 — closingIssuesReferences 또는 본문의 `Closes #N`으로 확인한다

   MERGED  → "이미 완료" 보고 후 중단
   OPEN    → 그 브랜치를 checkout 해서 **이어서 작업**(3단계 게이트 건너뜀)

3. git branch -a | grep issue-<N>
   있으면 그 브랜치 재사용. 새로 만들지 않는다
```

**중복 브랜치·중복 PR을 만들지 않는다.**

### 3단계 — 게이트 (다른 Issue의 미완 작업)

여기 오는 건 **새 작업일 때뿐**이다. 장부 둘을 모두 본다.

```
gh pr list --draft --state open --json number,title,headRefName
  → 브랜치가 feature/issue-* 또는 fix/issue-* 인 것만 센다
~/Dev/kleague-learning/REVIEW-QUEUE.md 의 "대기 중"
  → 파일이 없으면 오류가 아니라 대기 0개로 본다
```

**둘 중 하나라도 미완이면 중단**한다. Draft PR은 기계 판정이고, REVIEW-QUEUE는 *"근거와 기각한 대안까지 말할 수 있는가"*라는 사람 판정이다. **Ready로만 바꾸고 학습을 건너뛰면 Draft 게이트는 열려도 큐는 안 닫힌다** — 그래서 둘 다 본다.

중단 시 해당 PR 번호·큐 항목과 함께 보고한다.

- 허용되는 것: Follow-up Issue 생성 · 백로그 정리 · 문서 조회 · 질문
- 예외: 사용자가 **명시적으로** 긴급 예외를 선언한 경우

> 구현이 빨라진 만큼 미이해 코드가 쌓일 위험도 그만큼 커졌다. 이 게이트가 없으면 결과는 "이해 못 한 코드 더미 + merge 버튼"이다.

### 자연어인 경우

Issue가 범위 계약이므로 **Issue 없이 구현하지 않는다.** 먼저 만들고 합류한다.

```
1. gh issue list --state all --search "<핵심어>"  — 유사·중복 Issue 검색
   같은 작업이 명확하면 그 Issue 사용
   유사하나 확신이 안 서면 사용자에게 한 번만 질문
2. .github/ISSUE_TEMPLATE/feature.md 형식으로 본문을 채운다
   채우지 못한 절(특히 비목표·완료 조건)은 사용자에게 질문한다
3. 완성본을 보여주고 승인받은 뒤 gh issue create
4. 생성된 번호로 이슈번호 분기에 합류
```

---

## ② Design Freeze

Issue 본문과 근거 문서(`docs/superpowers/specs/`·`docs/adr/`·`docs/api/openapi.yaml`)를 읽고 접근을 정리해 **Issue 댓글**로 기록한다. 사용자가 쓴 본문은 함부로 고치지 않는다 — 저자와 시점이 다르므로 섞으면 누가 뭘 약속했는지 사라진다.

```markdown
## 구현 계약 및 Design Freeze

### Goal
### Acceptance Criteria
### Non-goals
### Invariants          <!-- 지켜야 할 도메인 불변식 -->
### Decisions           <!-- 내린 판단과 근거. 기각한 대안 포함 -->
### Calibration         <!-- 값 · 임시 근거 · 위치 · 조정 경계 -->
### Implementation Outline
### Verification        <!-- 실행할 명령 -->
```

**기록한 뒤 승인을 기다리지 않고 ③으로 간다.** "다음 단계로 진행할까요?" 같은 형식적 승인 요청은 하지 않는다.

멈추는 경우는 둘뿐이다 — ⑥의 정지 조건, 그리고 **①에서 Issue 본문을 새로 만들 때**(그건 범위 계약 자체라 사람이 승인해야 한다).

### Freeze 이후 설계를 다시 여는 조건

**허용:** 현재 설계로 인수 조건을 못 채움 · 계약/ADR/확정 규칙과 명백히 충돌 · 테스트가 실제 결함을 드러냄 · 데이터 무결성·동시성·보안·트랜잭션 경계 문제 · 사용자가 요청

**불허:** 더 예쁜 구조가 떠올랐다 · 다른 방식도 가능하다 · 미래 확장에 유용할 것 같다 · 더 범용적인 추상화가 가능하다 · 현재 인수 조건에 없는 개선점이 보였다

실제로 바뀌었으면 Issue에 **Design Change** 댓글을 추가한다 — 변경 내용 · 근거 · 영향 범위 · 수정한 테스트와 문서 · 현재 범위 안인지.

---

## ③ 구현

브랜치는 `feature/issue-<N>-<slug>` 또는 `fix/issue-<N>-<slug>`. **기본 브랜치에서 직접 작업하지 않는다.**

**새 브랜치는 현재 체크아웃된 브랜치가 아니라 최신 `origin/<기본 브랜치>`를 기준으로 만든다.** 기존 Issue 브랜치를 재개할 때만 그 브랜치를 checkout한다.

```bash
git remote show origin | sed -n 's/.*HEAD branch: //p'   # 기본 브랜치 확인
git fetch origin <기본브랜치>
git switch -c feature/issue-<N>-<slug> origin/<기본브랜치>
```

현재 브랜치에서 그냥 `checkout -b` 하면 **직전 작업의 커밋이 다음 PR에 섞인다.** 특히 squash merge 뒤 로컬에 남아 있는 브랜치 위에서 시작하면 이미 머지된 변경이 다시 올라온다.

미커밋 변경이 있으면: 현재 Issue와 관련 있으면 보존하고 이어 쓴다. 관련 없으면 되돌리거나 삭제하지 않고, `git add .`로 무분별하게 stage하지 않는다. 안전하게 분리할 수 없으면 코드 수정 전에 한 번만 보고한다. 사용자 동의 없이 stash·reset·checkout 복원을 하지 않는다.

### 순서

테스트를 먼저 쓰고 최소 구현으로 통과시킨다. 레이어별 파일만 만들고 기능을 미완성으로 남기지 않는다 — 작은 수직 단위로 완성한다.

### 이 저장소의 도메인 규칙

여기에 복사하지 않는다 — 두 곳에 있으면 한쪽만 고쳐서 썩는다(`CLAUDE.md`의 원칙).

**`CLAUDE.md`의 "구현 시 자주 틀리는 규칙" + `docs/api/README.md` 공통 규약 + `.claude/agents/code-reviewer.md`의 체크리스트**를 따른다. 셋 다 읽고 시작한다.

### 튜닝 상수는 정지 사유가 아니다

설계 스펙 3행이 *"튜닝 상수(공급 N·시작 포인트·수수료율·배당 가중치 등)는 **구현 시 캘리브레이션**"*이라고 정해 놨다. 값이 미확정이어도 **구현을 멈추지 않는다.**

- Issue의 캘리브레이션 항목에 **초기값 · 임시 근거 · 값의 위치 · 조정 경계**를 기록한다
- magic number로 흩뿌리지 말고 **도메인 정책 상수 또는 설정 프로퍼티**로 격리한다
- 운영 중 바뀔 값(수수료율·공급량)을 전부 `static final`로 고정하지 않는다

### 범위 밖은 손대지 않는다

Issue의 **비목표**가 판정 기준이다. 구현 중 발견한 개선점은 고치지 말고 기록한다.

```markdown
## Follow-up
- 발견한 내용 / 제외한 이유 / 예상 영향 / 별도 Issue 필요 여부
```

별도 Issue는 **독립 구현 가능 + 구체적 완료 조건 + 실제 결함이나 명확한 후속 요구**일 때만 만든다(`Follow-up of #<N>` 포함). 스타일 취향이나 막연한 확장 아이디어로 Issue 목록을 오염시키지 않는다. 나머지는 PR 본문 Follow-up 절에만 적는다.

---

## ④ 검증

좁은 것부터 실행한다.

```
1. 바꾼 테스트 클래스
2. 해당 모듈 테스트
3. ./gradlew build          — 컴파일 + 테스트 + 패키징
```

실패하면 원인을 분석하고(테스트 오류인지 구현 오류인지 구분) 고친 뒤 같은 테스트 → 회귀 → 전체 순으로 다시 돌린다. **테스트가 실패한 상태로 완료를 선언하지 않는다.** 실행하지 못한 것이 있으면 명령·오류 메시지·미검증 범위를 적는다. **돌리지 않은 테스트를 "통과했다"고 보고하지 않는다.**

### 자체 리뷰

`code-reviewer` 서브에이전트를 호출한다(읽기 전용, 스펙 D0~D9·ADR 준수 검사). 문서를 고쳤으면 `docs-auditor`도 호출한다.

보고서를 맹목 수용하지 않는다 — 근거 인용을 직접 확인한 뒤 반영한다.

- **명백한 스펙·계약 위반** → ③으로 돌아가 고치고 재검증
- **판단이 갈리는 것** → 고치지 말고 PR 본문 `## 리뷰 포인트 > ### 구현자가 확인받고 싶은 판단`에 적는다

---

## ⑤ 인계 — 커밋 → push → Draft PR

### 커밋

관련 파일만 stage한다. 사용자의 무관한 미커밋 변경을 포함하지 않는다. 논리적 단위로 나누고, 테스트가 깨진 중간 상태를 최종 커밋으로 남기지 않는다. `reset --hard`·강제 push·공유된 커밋 재작성을 하지 않는다.

메시지 규약:
- 본문은 **"무엇이 바뀌었나" 불릿만.** 왜 하는지를 산문으로 앞에 붙이지 않는다
- 본문 문장 끝에 **마침표를 붙이지 않는다**
- **Claude 관련 문구를 넣지 않는다** (`Co-Authored-By`, `Generated with` 등). PR 본문도 같다

### push

이 머신은 자격증명 조회에서 멈춘 이력이 있다. **먼저 읽기 프로브로 판별한다.**

```
GIT_TERMINAL_PROMPT=0 git ls-remote origin HEAD     # Bash 도구 timeout 15000ms
```

셸 `timeout` 바이너리가 없으므로 **Bash 도구의 `timeout` 파라미터(ms)**로만 끊는다.

- 프로브 성공 → `git push -u origin <브랜치>` (timeout 60000ms)
- 프로브 실패·무응답 → **push를 시도하지 않고** 사용자에게 그대로 복사 가능한 명령을 넘긴다

```
git push -u origin <브랜치>
gh pr create --draft --base <기본브랜치> --title "<제목>" --body-file <경로>
```

### Draft PR

- 같은 브랜치에 열린 PR이 있으면 **새로 만들지 않고 본문을 갱신**한다
- `--draft`로 만든다. **Ready for review로 자동 전환하지 않는다**
- `--base`는 고정 `main`이 아니라 **위에서 확인한 기본 브랜치**를 쓴다
- 본문에 **`Closes #<N>`**을 포함한다
- **merge하지 않는다. Issue를 직접 close하지 않는다**
- CI 상태를 **한 번** 확인하고 보고한다. pending이면 반복 polling하지 않는다

본문은 `.github/pull_request_template.md`의 절 구조를 따르고 실제 결과로 채운다. **존댓말로 쓴다**(기존 PR 문체).

`## 테스트`에는 전체 로그를 붙이지 않는다.

```markdown
| 명령 | 결과 |
|---|---|
| `./gradlew build` | 통과 |
- GitHub Actions Build: 통과
- 미검증: 없음
```

`## 리뷰 포인트`는 두 칸이다.

- **`### 구현자가 확인받고 싶은 판단`** → **채운다.** Design Freeze 이후 갈렸던 판단, 확신이 낮은 곳, ④에서 "판단이 갈린다"고 넘긴 것. 없으면 "없음"
- **`### 리뷰 메모`** → **비운다.** 무엇을 판단해 달라는지는 사용자가 `/learn` 세션에서 정한다

---

## ⑥ 정지 조건

아래에서는 즉시 멈추고 보고한다. 추측으로 진행하지 않는다.

| 조건 | 왜 |
|---|---|
| **권위 있는 계약끼리 충돌** | 스펙·ADR·`openapi.yaml` 중 어느 것을 따라야 할지 확정할 수 없다 |
| **계약 준수에 Issue 범위 밖 변경이 필요** | 계약 자체를 바꿔야 한다 — 현재 범위로 완료할 수 없다 |
| **Issue 범위로 구현 불가** | 선행 결정이 없다. ADR을 스스로 확정하지 않는다 |
| **미리뷰 Draft PR 존재** | ①의 게이트 |
| **고위험 결정** | 아래 목록 |
| **gh 인증·권한 실패** | Issue를 추측하거나 없는 번호를 만들지 않는다. GitHub 작업이 성공했다고 거짓 보고하지 않는다 |

**고위험 목록** — 공개 API 호환성 파괴 · DB 스키마·마이그레이션 · 기존 데이터 손실 가능성 · 인증·인가 정책 · 멀티모듈 경계 · 거래·동시성 모델 · 새 외부 의존성 · Java/Spring Boot/Gradle 주요 버전 · 기존 ADR을 대체하는 결정 · 사용자만 아는 비즈니스 정책이 없으면 구현 불가

질문이 여러 개면 **한 메시지로 묶는다.**

```markdown
## 구현을 막는 결정
### 확인된 사실
### 결정이 필요한 항목
### 선택지
### 추천안과 이유
### 답이 없을 때 적용할 기본안
```

---

## ⑦ Dry run

`--dry-run`이면 **조회와 출력만** 한다.

```
입력 판별 → Issue·PR·브랜치 조회 → 예상 Design Freeze → 예상 변경 파일 → 검증 명령 → 종료
```

**하지 않는 것:** Issue 생성 · 댓글 · 파일 수정 · 브랜치 생성 · commit · push · PR 생성

---

## 하지 않는 것

- **merge** — 사용자의 권한이다. `gh pr merge`를 호출하지 않는다
- **ADR 확정** — 초안 작성은 허용하지만 확정은 사용자가 한다
- **Issue 범위 밖 리팩터링**
- **학습 노트 작성** — `/learn`의 몫이다. 사용자가 리뷰하기 전에 노트를 쓰면 "판단과 버린 대안"이 내 작업 로그가 되어 학습 기록이 아니게 된다
- **기존 Issue 본문 전면 교체** — 보완은 댓글로

---

## 참고 — 승인과 권한 프롬프트는 다르다

이 스킬은 **계획 승인을 요청하지 않는다.** 다만 `git push`·`gh issue create`·`gh pr create`는 권한 설정상 **도구 권한 프롬프트**가 뜰 수 있다. 이 둘은 다른 것이다 — 전자는 워크플로가 없앤 왕복이고, 후자는 원격에 접촉한다는 신호다.
