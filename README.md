# skala-helpdesk-ai

**SKALA University 학사 안내 HelpDesk AI — SpringAI 종합 실습 (교안 p.307–322)**

학생이 자연어로 물으면 학사규정을 근거로 답하고, 본인 학적을 실시간으로 조회하며,
수강철회를 접수하는 상담 에이전트다.

- **규정 질의응답** — 학칙·학사운영에관한규칙·장학금에관한규칙에서 찾아 답하고 출처를
  함께 준다. 근거가 없으면 지어내지 않는다.
- **본인 학적 조회** — 학점·GPA는 문서가 아니라 DB에서 온다. 학번은 인증 주체에서만
  흐르고 모델이 만들어 낼 수 없다.
- **수강철회 접수** — 일부러 끝까지 처리하지 않는다. `PENDING` 접수까지만 하고 승인은
  사람이 한다.

학사 안내로 정한 건 이 셋이 한 대화 안에서 자연스럽게 이어지고, 실제 규정 PDF를 쓸 수
있어서다. 예제용 짧은 문서로는 검색 실패나 조항 혼입 같은 문제가 드러나지 않는다.

## 2인 분담

전문 영역은 나누되 상대 기능을 반드시 리뷰하는 구조다. 전체 내용은
[`docs/분업-역할표.md`](docs/분업-역할표.md) 참조.

| | 황재원 — AI·RAG·플랫폼 | 박성우 — 업무 API·Tool·보안 |
|---|---|---|
| 흐름 | 모델 설정 → 인제스트 → 벡터 검색 → RAG 답변 → 메모리 → 계측 → 폴백 | 인증 → API → 학적 조회 → Tool → 권한 → 수강철회 → 감사 → 테스트 |
| 담당 파일 | `config/AiConfig`·`HelpDeskProperties`, `rag/IngestService`, `chat/HelpDeskService`, `advisor/TokenMeterAdvisor`·`SafeGuardAdvisor` | `web/*`, `tools/*`, `repository/*`, `chat/AnswerDto`, `advisor/AuditAdvisor`, `eval/GoldenSet` |
| 완료 기준 | 근거 있는 답변·출처, 메모리 격리, 계측, 폴백 | 소유자 검증, 승인 게이트, 감사 로그, 레드팀 10종 |

담당자는 첫 번째 책임자이고, 상대는 두 번째 담당자로서 코드 리뷰와 실행 검증에 참여한다.
`AiConfig`(황재원 소유)는 박성우가 보안 순서를, `AnswerDto`·`ChatController`(박성우 소유)는
황재원이 RAG 연결을 검토한다. 전체 표는 `docs/분업-역할표.md`의 "파일 충돌 방지 규칙" 참고.

PR 16건에 리뷰 18건이 달렸고 양쪽이 9건씩 남겼다. 리뷰가 실제로 잡아낸 결함은
[`docs/보고서.md`](docs/보고서.md) 6.2절에 정리돼 있다.

## 지금 상태 — Phase 1~8 완료

출발점은 `TODO(A, Phase N)` / `TODO(B, Phase N)` 마커가 박힌 스캐폴드였다. 지금은 그
마커가 하나도 없고(`grep -rn "TODO(" src/main/java`), RAG 답변·Tool 응답·인증·감사·
계측·폴백이 모두 동작한다.

| | |
|---|---|
| 자동 테스트 | 클래스 20개 · **135개 통과** (모델을 호출하지 않는다) |
| 인제스트 | 문서 6종 → 청크 306건 (md 3종 + 학사규정 PDF 3종) |
| 검증 시나리오 | **7종 전부 통과** |
| 레드팀 | **10종 전부 통과** |
| Golden Set | **20/20 · 전 지표 100%**, 비스트리밍 P95 2,812ms |

```bash
./gradlew build           # 단위·통합 테스트 전체. API 키 없이도 돌아간다
./gradlew goldenSetTest   # 실제 OpenAI·pgvector로 20문항 평가. 키와 pgvector 필요
```

평가 결과는 `build/reports/golden-set/results.md`에 생성된다.

**측정하지 않은 항목을 통과로 적지 않는다**가 이 리포의 기록 원칙이다. 각 검증 문서의
`☑`에는 실행 결과가 근거로 붙어 있고, 재현하지 못한 항목은 `☐`로 남겨 이유를 적었다.

## 실행

```bash
docker compose up -d          # pgvector (Phase 2·5가 여기 저장한다)
cp .env.example .env          # OPENAI_API_KEY 채우고 export 하거나 IDE에 주입
export OPENAI_API_KEY="sk-..."
./gradlew bootRun             # VS Code는 F5
```

로컬 PostgreSQL이 5432 포트를 이미 사용 중이면 저장소 설정을 바꾸지 않고
다음처럼 Docker 공개 포트와 실행 시 datasource URL만 재정의한다.

```bash
HELPDESK_POSTGRES_PORT=15432 docker compose up -d
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/helpdesk ./gradlew bootRun
```

Swagger UI — <http://localhost:8080/swagger-ui.html>
Health — <http://localhost:8080/actuator/health>

Phase 7부터 인증이 걸려 있다 — 인증 없이 부르면 401이다. 아래 계정은 로컬 재현용
기본값이고 `HELPDESK_STUDENT_PASSWORD`·`HELPDESK_ADMIN_PASSWORD`로 교체한다.

```bash
curl -u 2021001:student -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
     -d '{"question":"졸업 학점 요건이 어떻게 돼요?","sessionId":"s1"}'
curl -u admin:admin localhost:8080/api/admin/withdrawal-requests/pending

# 관리자 청크 진단 — 한글 질의는 반드시 URL 인코딩한다(그냥 붙이면 400)
curl -u admin:admin -G localhost:8080/api/admin/chunks \
     --data-urlencode "q=졸업 요건" --data-urlencode "topK=10" --data-urlencode "threshold=0"
```

## 문서

- [`docs/보고서.md`](docs/보고서.md) — **제출 보고서.** 구현 내용·검증 결과·차별화
  포인트를 실행 캡처와 함께 정리했다. 여기부터 읽으면 전체가 보인다
- [`docs/검증-시나리오.md`](docs/검증-시나리오.md) — 완료 시나리오 7종과 실측 기록
- [`docs/레드팀-체크리스트.md`](docs/레드팀-체크리스트.md) — 레드팀 10종
- [`docs/골든셋-평가.md`](docs/골든셋-평가.md) — Golden Set 20문항·합격 기준·교정 이력
- [`docs/분업-역할표.md`](docs/분업-역할표.md) — 2인 분담과 파일 소유권
- [`AGENTS.md`](AGENTS.md) — 코딩 에이전트 작업 규약(Claude Code·Codex 공용)
- [`http/samples.http`](http/samples.http) — 시나리오별 요청 샘플(REST Client 확장)

## 설계에서 지킨 세 가지 경계

프롬프트가 아니라 코드로 강제한다. 자세한 내용은 보고서 2.3절에 있다.

1. **소유자 검증** — 학번은 Tool 파라미터가 아니라 `ToolContext`로 흐른다. 조회는
   `findByIdAndOwnerId` 하나만 쓰고, "없는 학번"과 "남의 학번"을 구분하지 않는다
2. **승인 게이트** — `approve`는 `AdminController`에만 있고 Tool 목록에 없다. 모델이
   지시받아도 닿을 수 없다
3. **`conversationId`** — `HelpDeskService` 한 곳에서만 만든다(`skala:{학번}:{세션}`)

Advisor 순서도 정책이다 — `Audit(0) → TokenMeter(10) → SafeGuard(100) → Memory(200) →
RAG(300) → ToolCalling(350)`. SafeGuard가 Memory보다 앞이어야 차단된 입력이 대화 이력에
남지 않는다.

## 패키지 구조

```
config/     AiConfig · HelpDeskProperties · ChatProperties · AiOpsProperties · ChatMemoryConfig   (황재원)
rag/        IngestService · RetrievalGuard · GuardedVectorStore                                    (황재원)
chat/       HelpDeskService · StreamEvent (황재원) · AnswerDto (박성우, 공동 계약)
advisor/    TokenMeterAdvisor · SafeGuardAdvisor (황재원) · AuditAdvisor · ToolAuditAspect (박성우)
web/        ChatController · AdminController · SecurityConfig · TraceIdFilter                      (박성우)
tools/      AcademicTools · RequestTools (박성우) · BoundedToolCallingManager (황재원)
repository/ StudentRecordRepository · WithdrawalRequestRepository                        (완성 제공)
domain/     Enrollment · EnrollmentStatus · StudentRecord · WithdrawalRequest · RequestStatus (완성 제공)
eval/       GoldenSet                                                                          (박성우)
```

## 버전 계열

Boot 4.1.0 / `spring-ai-bom:2.0.0` / JDK 21 — `day1-order-summary`·`day3-consult-agent`와
동일 계열(CLAUDE.md 고정값). Boot 4부터 `spring-boot-starter-aop`가 아니라
`spring-boot-starter-aspectj`를 쓴다(day3-consult-agent에서 실컴파일로 확인).

`spring-ai-starter-vector-store-pgvector`·`spring-boot-starter-security`·
`spring-ai-starter-model-chat-memory-repository-jdbc` 3개는 이 리포에서 처음 추가한
좌표다 — `./gradlew build`로 2.0.0 BOM에서 실제로 해석되고 컴파일이 통과함을
확인했다(2026-08-20). pgvector·실제 API 키를 사용한 `bootRun`, Health,
Swagger UI도 확인했다(2026-08-21). 로컬 PostgreSQL과의 5432 포트 충돌은
위의 `HELPDESK_POSTGRES_PORT`와 `SPRING_DATASOURCE_URL` 재정의로 해결한다.

## 참조 코드

- 황재원: `SpringAI_실습/ch07_rag`·`ch08_ragadv`(인제스트), `ch05_structured`(구조화 응답),
  `12_Advisor순서`(SafeGuard·order 실험), `ch11_advisors`(토큰 계측), `ch12_ops`·
  `13_병목과캐시`(폴백)
- 박성우: `ch09_tools`(도구+권한검증), `11_승인게이트`(감사 AOP+승인 게이트),
  `14_SSE와추적ID`(SSE), `ch10_toolsafe`(Spring Security + `@PreAuthorize`)
- 공통: `day3-consult-agent`(같은 워크스페이스) — Day 3 메인 실습 완성본. Tool·Advisor·
  감사·계측의 실동작 참조 구현이다.
