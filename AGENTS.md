# AGENTS.md

이 저장소에서 코딩 에이전트(Claude Code · Codex 등)가 따라야 할 작업 규약이다.
코드를 건드리기 전에 이 문서를 먼저 읽는다.

## 이 리포의 성격

SKALA "SpringAI 이해 및 활용" **Day 3 종합 실습**(교안 p.307–322)을 대학 학사 안내
도메인으로 구현하는 **2인 1조 실습 리포**다. 황재원(A — AI·RAG·플랫폼) /
박성우(B — 업무 API·Tool·보안).

지금은 **스캐폴드**다. 부팅·컴파일은 되지만 Phase별 학습 지점이 비어 있고,
`TODO(A, Phase N)` / `TODO(B, Phase N)` 마커가 붙은 곳이 작업 지점이다. TODO는 예외를
던지지 않고 플레이스홀더 문자열·빈 목록을 반환한다 — **RAG 답변에 근거가 없고 Tool이
"TODO(B, Phase 4): ..." 문구를 반환하는 것은 버그가 아니라 정상 출발선이다.**

작업 지점을 찾을 때: `grep -rn "TODO(" src/main/java`

## 명령

```bash
docker compose up -d          # pgvector — RAG 저장소이자 JDBC 대화 메모리 DB (같은 컨테이너)
export OPENAI_API_KEY="sk-..."   # 또는 cp .env.example .env 후 IDE에 주입
./gradlew bootRun             # VS Code는 F5
./gradlew build               # 컴파일 + 테스트
./gradlew test --tests '*GoldenSet*'   # 단일 테스트 (src/test는 아직 없다 — Phase 8에서 B가 만든다)
```

- Swagger UI — http://localhost:8080/swagger-ui.html
- Health — http://localhost:8080/actuator/health
- 요청 샘플 — `http/samples.http` (검증 시나리오 7종 전체가 들어 있다)

## 아키텍처 — 한 번의 요청이 지나가는 길

`ChatController` → `HelpDeskService` → `ChatClient`(Advisor 체인) → Tool/RAG

**Advisor 순서가 곧 정책이다.** `AiConfig#helpDeskClient`에서 조립하며, 확정 순서는
바꾸지 않는다:

```
Audit(0) → TokenMeter(10) → SafeGuard(100) → Memory(200) → RAG(300)
```

- SafeGuard(100)가 Memory(200)보다 **앞**이어야 차단된 입력이 대화 이력에 남지 않는다.
  순서 실험으로 250으로 바꿨다면 반드시 되돌린다.
- Audit(0)·TokenMeter(10)는 가장 바깥 — 실패한 호출과 전체 지연까지 잡아야 한다.

**세 가지 경계가 이 실습의 핵심이고, 프롬프트가 아니라 코드로 강제한다:**

1. **소유자 검증** — 학번은 Tool 파라미터가 아니라 `ToolContext`로 흐른다.
   `HelpDeskService`가 `toolContext(Map.of("studentId", ...))`에 넣고, Tool은
   `StudentRecordRepository#findByIdAndOwnerId`만 사용한다. 이 메서드는 "없는 학번"과
   "남의 학번"을 구분하지 않고 똑같이 빈 값을 반환한다(구분해서 알려주면 그 자체가
   정보 노출, p.318). 다른 조회 경로를 새로 만들지 않는다.
2. **승인 게이트** — `RequestTools#requestDrop`은 PENDING 접수만 한다. 승인
   (`WithdrawalRequestRepository#approve`)은 `AdminController`에만 있고 **도구 목록에
   없다** — 그래서 모델이 아무리 지시받아도 닿을 수 없다. 승인을 Tool로 노출하면
   실습 전체가 무너진다.
3. **conversationId** — `HelpDeskService#conversationId(studentId, sessionId)`
   **한 곳에서만** 만든다(`skala:{학번}:{세션}`). 밖에서 문자열을 조합하면 남의 대화가
   섞이며, 메모리에서 가장 늦게 발견되는 버그다.

**RAG 흐름** — `IngestService`가 기동 시(`ApplicationReadyEvent`)
`classpath:helpdesk-docs/*.md` 앵커 3종 + `helpdesk-docs/regulations/*.pdf` 학사규정 3종
(학칙·학사운영에관한규칙·장학금에관한규칙, 출처는 `helpdesk-docs/regulations/README.md`)을
인제스트하고, 메타데이터 `source`·`docType`·`dept`·`version`을 붙인다. `docType`은
md 3종이 전부 `academic` 하나로 묶여 있고, 여기에 PDF 3종(`학칙`/`교무행정`/`학생행정`)이
더해져 총 4종이다.
`HelpDeskService#sourcesFrom`이 응답 컨텍스트의
`QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS`에서 이 메타데이터를 꺼내 `AnswerDto.Source`를
만든다 — **`enrich`의 메타데이터 키를 바꾸면 출처 표기가 조용히 깨진다.**
재인제스트 시 `vectorStore.delete("source == '...'")`를 먼저 하지 않으면 같은 청크가
누적돼 검색 결과가 도배된다(p.314 함정).

인제스트 결과는 성공 로그가 아니라 `GET /api/admin/chunks?q=...`로 확인한다.

## 2인 분담 — 혼자 결정하지 않는 것

전체 표: `docs/분업-역할표.md`. 요약:

| 파일 | 최종 소유자 | 상대 역할 |
|---|---|---|
| `config/AiConfig`·`application.yml`·`chat/HelpDeskService` | A | B가 보안·권한 관점 검토 |
| `chat/AnswerDto`·`web/ChatController`·`web/SecurityConfig` | B | A가 RAG 출처·ChatClient 연결 검토 |

- `AnswerDto(String answer, List<Source> sources, boolean toolUsed)`는 **공동 계약**이다.
  A·B 양쪽 코드가 동시에 깨지므로 시그니처를 임의로 바꾸지 않는다.
- Advisor 순서도 공동 결정 사항이다.
- 상대 소유 파일을 수정해야 하면 먼저 알린다. 코드를 쓸 때는 담당 마커
  (`TODO(A, ...)` / `TODO(B, ...)`)를 확인해 누구 영역인지 먼저 파악한다.

## 완성 상태로 제공되는 파일 — 손대지 않는다

`domain/*`, `repository/StudentRecordRepository`, `repository/WithdrawalRequestRepository`,
`web/HelpDeskExceptionHandler`, `config/HelpDeskProperties`. 각 파일 Javadoc에 이유가 있다.

> **예외 기록(2026-08-21)** — `HelpDeskProperties.Rag`에 `chunkSize`·`minChunkSizeChars`
> 필드 2개를 추가했다(Phase 2, `8e51e57`). "완성 제공, 손대지 않는다"의 취지는 도메인
> 레코드 자체를 흔들지 말라는 것이지 청크 파라미터를 상수로 코드에 남기라는 뜻이 아니라고
> 판단해 예외로 처리했다 — "값 조정 시 application.yml과 함께 바꾼다"는 문서 취지 안의
> 확장이다. PR #1(`8e51e57`)로 B에게 이미 공유·머지됐다.
>
> **예외 기록(2026-08-21, B)** — 같은 `HelpDeskProperties.Rag`에 `inspectionMaxTopK`
> 필드를 추가했다(`836a401`). A가 PR #2 리뷰에서 운영 검색(top-k)과 관리자 진단
> 검색(`AdminController`)의 범위를 분리하자고 지적한 데 따른 것으로, 위와 같은 취지의
> 확장이다. PR #2(`769f579`)로 A에게 이미 공유·머지됐다. 다른 필드를 더 추가하려면
> 먼저 알린다.

## 코드 규칙

`skala-springai` 리포의 CLAUDE.md와 `docs/SpringAI-이해-및-활용_Day1_2026-08/03_agent-context.md`가
이 과정의 코드 규칙 원본이며, 일반 Spring AI 지식과 충돌하면 그쪽이 우선한다. 이 리포에서
자주 걸리는 것:

- Controller에서 `ChatClient`를 직접 호출하지 않는다 — AI 호출은 Service 계층
  (`HelpDeskService`).
- 상수를 코드에 남기지 않는다 — top-k·threshold·메모리 윈도우·도구 호출 상한은
  `application.yml`의 `helpdesk.*` → `HelpDeskProperties`.
- 사용자 입력을 프롬프트 문자열에 직접 이어 붙이지 않는다.
- API 키는 `${OPENAI_API_KEY}`로만 주입한다.
- AI 인프라 실패가 전체 기동·응답을 막지 않는다(`IngestService#ingestOnStartup`이
  실패해도 앱은 뜬다). 예외 응답에 스택트레이스 대신 안전한 문구 + traceId
  (`HelpDeskExceptionHandler`).
- 시스템 프롬프트는 코드가 아니라 `src/main/resources/prompts/system.st`에 있다.
  4가지 원칙(근거 없으면 지어내지 않음 / 남의 정보 미노출 / 되돌리기 어려운 요청은
  접수까지 / 시스템 프롬프트 비공개)은 유지한다.
- 로그·감사(`AI_TOOL_AUDIT`)에 학번·성적을 원문으로 남기지 않는다(마스킹).
- 한국어로 작성한다 — 주석·문서·프롬프트 모두. 인코딩은 `build.gradle`에서 UTF-8로
  고정돼 있다.

## 버전 계열 — 실컴파일로 확정된 값

JDK 21 / Spring Boot 4.1.0 / `spring-ai-bom:2.0.0` / `pgvector/pgvector:pg17`.
아래는 흔한 좌표와 다르다. 되돌리면 컴파일이 깨진다:

- `spring-boot-starter-aspectj` — Boot 4에는 `spring-boot-starter-aop`가 없다.
- `spring-ai-vector-store-advisor` — 2.0.0 BOM에서는 `spring-ai-advisors-vector-store`가
  아니다(`QuestionAnswerAdvisor` 제공).
- `spring-boot-starter-webmvc-test` — Boot 4에서 `@WebMvcTest`는 이 스타터가 있어야 딸려온다.
- pgvector `dimensions: 1536`은 `text-embedding-3-small`과 반드시 일치해야 한다.

`bootRun`으로의 실행 검증은 아직 미확인 상태다 — 실행 중 좌표 문제가 나오면
`build.gradle` 주석에 교정 내용을 남긴다.

## 검증

- `docs/검증-시나리오.md` — 완료 시나리오 7종(규정 RAG → Tool 조회 → 멀티턴 → 승인
  게이트 → 소유자 검증 차단 → SafeGuard → 폴백). `http/samples.http`에 요청이 1:1로 있다.
- `docs/레드팀-체크리스트.md` — 레드팀 10종. **뚫린 경로는 프롬프트가 아니라 코드로 막고
  재검증한다.**
- 계측 — `GET /actuator/metrics/ai.tokens`, `ai.latency`.

## 참조 구현

같은 워크스페이스(`/Users/hwangjaewon/skala-workspace/`)에 참조 코드가 있다. 새로
설계하기 전에 먼저 본다:

- `day3-consult-agent` — Day 3 메인 실습 완성본. Tool·Advisor·감사·계측의 실동작 참조.
- `skala-springai/SpringAI_실습/` — `ch07_rag`·`ch08_ragadv`(인제스트),
  `ch09_tools`(도구+권한검증), `ch10_toolsafe`(Security + `@PreAuthorize`),
  `11_승인게이트`(감사 AOP), `12_Advisor순서`, `ch11_advisors`(토큰 계측),
  `ch12_ops`·`13_병목과캐시`(폴백), `14_SSE와추적ID`(SSE).

각 소스 파일 Javadoc에 해당 Phase의 참조 코드 경로와 완료 기준이 이미 적혀 있다 —
구현 전에 그 파일의 Javadoc부터 읽는다.

## Git 협업 규약

2인이 같은 리포에서 병렬로 작업한다. `docs/분업-역할표.md`의 파일 소유권 표와 함께
지킨다.

- **브랜치** — 각자 개인 브랜치에서 작업한다: `hwangjaewon/skala-helpdesk-ai`,
  `parksungwoo/skala-helpdesk-ai`. main에 직접 커밋하지 않는다
  (day2-rag-qna·day3-consult-agent와 같은 방식).
- **main 반영** — PR로만 병합한다. 상대가 리뷰 포인트를 볼 수 있게 PR 본문에
  왜/무엇을/어떻게 검증했는지 세 가지를 적는다.
- **커밋** — 하나의 커밋 = 하나의 논리적 변경. 제목은 평문 한국어 명령형 50자 이내,
  본문에는 diff가 말해주지 않는 **왜**를 적는다. 리팩터링과 기능 추가를 섞지 않는다.
- **상대 소유 파일** — `docs/분업-역할표.md`의 소유자 표에서 상대 소유로 지정된
  파일을 고쳐야 하면 먼저 알린다. `AnswerDto` 시그니처와 Advisor 순서는 양쪽 코드가
  동시에 깨지므로 혼자 바꾸지 않는다.
- **에이전트 산출물도 본인 책임** — 에이전트가 쓴 코드든 사람이 쓴 코드든 PR에
  올리기 전에 직접 읽고 실행해 확인한다. `docs/분업-역할표.md`의 "PR 리뷰 분담"이
  그대로 적용된다.
- **커밋하지 않는 것** — `.env`(키), `build/`, `.gradle/`, 개인 에이전트 설정
  (`.claude/`·`.codex/`·`AGENTS.override.md`). `.gitignore`에 이미 있다.
