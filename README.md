# skala-helpdesk-ai

**SKALA University 학사 안내 HelpDesk AI — Day 3 종합 실습 (교안 p.307–322)**

도메인은 **대학 학사 안내**로 정했다 — 규정 문서(RAG)·실시간
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

## 지금 상태 — Phase 1~8 구현 완료

출발점은 `TODO(A, Phase N)` / `TODO(B, Phase N)` 마커가 박힌 스캐폴드였다. 지금은 그
마커가 **하나도 남아 있지 않고**(`grep -rn "TODO(" src/main/java`로 확인), RAG 답변·
Tool 응답·인증·감사·계측·폴백이 모두 실제로 동작한다.

- `./gradlew build` — 단위·통합 테스트 전체(모델을 호출하지 않는다)
- `./gradlew goldenSetTest` — 실제 OpenAI·pgvector로 20문항 평가. 키와 실행 중인
  pgvector가 필요하다. 결과는 `build/reports/golden-set/results.md`.

남은 것과 후속 과제는 [`docs/검증-시나리오.md`](docs/검증-시나리오.md)·
[`docs/레드팀-체크리스트.md`](docs/레드팀-체크리스트.md)·
[`docs/골든셋-평가.md`](docs/골든셋-평가.md)에 `☐`로 표시돼 있다 —
**측정하지 않은 항목을 통과로 적지 않는 것**이 이 리포의 기록 원칙이다.

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

## 검증

- [`docs/검증-시나리오.md`](docs/검증-시나리오.md) — 완료 시나리오 7종 체크리스트
- [`docs/레드팀-체크리스트.md`](docs/레드팀-체크리스트.md) — 레드팀 10종
- [`docs/골든셋-평가.md`](docs/골든셋-평가.md) — Phase 8 Golden Set 20문항·합격 기준
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
