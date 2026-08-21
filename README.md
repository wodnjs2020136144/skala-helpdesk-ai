# skala-helpdesk-ai

**SKALA University 학사 안내 HelpDesk AI — Day 3 종합 실습 (교안 p.307–322)**

대응하는 강사 제공 Gradle 프로젝트가 없다 — 조별로 신규 구현하는 캡스톤 실습이다.
교안 자세한 설계 배경은 강의 자료 리포지토리 `skala-springai`의
`docs/SpringAI-이해-및-활용_Day3_2026-08/02_lab-guide.md` "종합 실습" 절과, 2인 분담
기준은 같은 디렉터리의 `04_종합실습_2인분담_가이드.md`를 참조.

도메인은 교안의 이커머스 CS 대신 **대학 학사 안내**로 정했다 — 규정 문서(RAG)·실시간
학적 조회(Tool)·승인이 필요한 쓰기(수강철회)·소유자 검증·관리자 권한·멀티턴 대명사
질문이 모두 자연스럽게 성립하는 도메인이다.

## 2인 분담

전문 영역은 나누되 상대 기능을 반드시 리뷰하는 구조다. 전체 내용은
[`docs/분업-역할표.md`](docs/분업-역할표.md) 참조.

| | A — AI·RAG·플랫폼 | B — 업무 API·Tool·보안 |
|---|---|---|
| 흐름 | 모델 설정 → 인제스트 → 벡터 검색 → RAG 답변 → 메모리 → 계측 → 폴백 | 인증 → API → 학적 조회 → Tool → 권한 → 수강철회 → 감사 → 테스트 |
| 담당 파일 | `config/AiConfig`·`HelpDeskProperties`, `rag/IngestService`, `chat/HelpDeskService`, `advisor/TokenMeterAdvisor`·`SafeGuardAdvisor` | `web/*`, `tools/*`, `repository/*`, `chat/AnswerDto`, `advisor/AuditAdvisor`, `eval/GoldenSet` |
| 완료 기준 | 근거 있는 답변·출처, 메모리 격리, 계측, 폴백 | 소유자 검증, 승인 게이트, 감사 로그, 레드팀 10종 |

담당자는 **첫 번째 책임자**이고, 상대는 **두 번째 담당자로서 코드 리뷰·실행 검증에
반드시 참여**한다. `AiConfig`(A 소유)는 B가 보안 순서를, `AnswerDto`·`ChatController`
(B 소유)는 A가 RAG 연결을 검토한다 — 전체 표는 `docs/분업-역할표.md`의 "파일 충돌
방지 규칙" 참고.

## 지금 상태 — 스캐폴드

**부팅은 되지만 Phase별 학습 지점은 비어 있다.** 아래 표시가 붙은 파일이 TODO다.

- `TODO(A, Phase N)` — A 담당
- `TODO(B, Phase N)` — B 담당

TODO는 예외를 던지지 않고 최소 반환값(플레이스홀더 문자열·빈 목록 등)으로 채워져
있다 — 컴파일은 통과하고 앱도 뜨지만, 실제 RAG 답변·Tool 응답은 채워 넣기 전까지
의미 없는 문구가 나온다. 그게 정상 출발선이다.

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

```bash
curl -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
     -d '{"question":"졸업 학점 요건이 어떻게 돼요?","sessionId":"s1"}'
curl localhost:8080/api/admin/withdrawal-requests/pending
```

## 검증

- [`docs/검증-시나리오.md`](docs/검증-시나리오.md) — 완료 시나리오 7종 체크리스트
- [`docs/레드팀-체크리스트.md`](docs/레드팀-체크리스트.md) — 레드팀 10종
- [`http/samples.http`](http/samples.http) — 위 시나리오의 요청 샘플(REST Client 확장으로 실행)

## 패키지 구조

```
config/     AiConfig(A) · HelpDeskProperties(A) · ChatMemoryConfig(A)
rag/        IngestService(A)
chat/       HelpDeskService(A) · AnswerDto(B, 공동 계약)
advisor/    TokenMeterAdvisor(A) · SafeGuardAdvisor(A) · AuditAdvisor(B)
web/        ChatController(B) · AdminController(B) · SecurityConfig(B) · HelpDeskExceptionHandler(공통, 완성)
tools/      AcademicTools(B) · RequestTools(B)
repository/ StudentRecordRepository(B, 완성) · WithdrawalRequestRepository(B, 완성)
domain/     Enrollment · EnrollmentStatus · StudentRecord · WithdrawalRequest · RequestStatus (완성)
eval/       GoldenSet(B)
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

- A: `SpringAI_실습/ch07_rag`·`ch08_ragadv`(인제스트), `ch05_structured`(구조화 응답),
  `12_Advisor순서`(SafeGuard·order 실험), `ch11_advisors`(토큰 계측), `ch12_ops`·
  `13_병목과캐시`(폴백)
- B: `ch09_tools`(도구+권한검증), `11_승인게이트`(감사 AOP+승인 게이트),
  `14_SSE와추적ID`(SSE), `ch10_toolsafe`(Spring Security + `@PreAuthorize`)
- 공통: `day3-consult-agent`(같은 워크스페이스) — Day 3 메인 실습 완성본. Tool·Advisor·
  감사·계측의 실동작 참조 구현이다.
