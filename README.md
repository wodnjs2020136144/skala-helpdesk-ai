# SKALA University 학사 안내 HelpDesk AI

학생이 자연어로 물으면 **학사규정을 근거로 답하고**, 본인 학적을 실시간으로 조회하며,
수강철회를 접수하는 상담 에이전트.

![JDK](https://img.shields.io/badge/JDK-21-red)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F)
![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.0-6DB33F)
![pgvector](https://img.shields.io/badge/pgvector-pg17-336791)
![tests](https://img.shields.io/badge/tests-135%20passing-brightgreen)
![golden set](https://img.shields.io/badge/Golden%20Set-20%2F20-brightgreen)

> SpringAI 이해 및 활용 — 종합 실습 (교안 p.307–322)
> 판교캠퍼스 10반 · 2인 1조 — 박성우(`P322`) · 황재원(`P345`)

한 세션 안에서 **규정 검색(RAG) → 본인 학적 조회(Tool) → 대명사 후속 질문(메모리)** 이
이어진다. 아래는 실제 응답이다.

![3턴 대화](docs/보고서-이미지/03-시나리오123.png)

- **규정 질의응답** — 학칙·학사운영에관한규칙·장학금에관한규칙에서 찾아 답하고 출처를
  함께 준다. 근거가 없으면 지어내지 않는다.
- **본인 학적 조회** — 학점·GPA는 문서가 아니라 DB에서 온다. 학번은 인증 주체에서만
  흐르고 모델이 만들어 낼 수 없다.
- **수강철회 접수** — 일부러 끝까지 처리하지 않는다. `PENDING` 접수까지만 하고 승인은
  사람이 한다.

학사 안내로 정한 건 이 셋이 한 대화 안에서 자연스럽게 이어지고, 실제 규정 PDF를 쓸 수
있어서다. 예제용 짧은 문서로는 검색 실패나 조항 혼입 같은 문제가 드러나지 않는다.

## 실제로 겪고 고친 것

교안대로만 만들었으면 지나쳤을 문제들이다. 전부 실측으로 찾아 **프롬프트가 아니라
코드로** 고쳤다. 자세한 경위는 [`docs/보고서.md`](docs/보고서.md) 7장에 있다.

### 1. 어느 규정에도 없는 "26학점"

"한 학기에 최대 몇 학점까지 신청할 수 있나요?"에 모델이 **26학점**이라고 답했다.
23학점(부칙의 1999학년도 이전 특례)과 +3학점(본칙)을 뒤섞은 환각이었다. 검색을 열어
보니 정답 조문이 상위에 아예 없었다.

인제스트에서 **본칙/부칙을 갈라** 운영 검색은 본칙만 보게 하고, Tika가 본문에 섞어 뽑은
**페이지 머리말·꼬리말을 제거**했다. 두 번째 조치가 없으면 이번엔 "18학점"이 나온다.

![RAG 교정](docs/보고서-이미지/10-rag교정.png)

### 2. 위조 문서가 공식 출처로 표기되던 것

`SafeGuardAdvisor`는 사용자가 친 입력만 검사한다. **검색된 문서 본문은 아무도 안 봤다.**
실제 규정 어휘를 흉내 낸 위조 문서에 `[SYSTEM OVERRIDE]` 지시를 심어 재현했더니,
답변 탈취까지는 안 갔지만 **그 문서가 `sources`에 공식 출처처럼 실렸다.**

인제스트 시 **문서 전체 텍스트로** 판정해 걸러낸다. 청크 단위로 검사했더니 지시문이
청크 경계에 갈려 뒷토막만 걸려 막히지 않았다.

![간접 인젝션 방어](docs/보고서-이미지/11-간접인젝션.png)

### 3. 스트림이 끊기면 다음 턴이 깨지던 것

동기 경로에는 있던 메모리 복구가 스트리밍에는 없었다. 토큰이 96개 나간 뒤 끊어도
**assistant 메시지는 저장되지 않아** 질문만 남았고, 그 뒤 "방금 설명해준 거 다시
정리해줘"를 보내면 모델이 가리킬 답을 찾지 못했다.

오류는 `doOnError`, 타임아웃·연결 끊김은 `doOnCancel`로 나눠 잡는다. `doFinally` 하나로
묶었더니 종료 신호가 다운스트림에 먼저 전달돼 경합이 났다(교차 리뷰 지적).

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

| 항목 | 값 |
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

![Golden Set](docs/보고서-이미지/16-골든셋.png)

이 20/20은 처음부터 나온 숫자가 아니다. **첫 평가는 75%로 실패**했고, 기준을 낮추는
대신 원인을 찾는 과정에서 위의 세 문제가 드러났다.

**측정하지 않은 항목을 통과로 적지 않는다**가 이 리포의 기록 원칙이다. 각 검증 문서의
`☑`에는 실행 결과가 근거로 붙어 있고, 재현하지 못한 항목은 `☐`로 남겨 이유를 적었다.

## API

상담 4종과 관리자 3종. 관리자 API는 `ADMIN`만 접근할 수 있고, 수강철회 **승인은 여기에만
있으며 모델의 Tool 목록에는 없다.**

![Swagger UI](docs/보고서-이미지/01-swagger.jpg)

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

`2021002`로 인증하고 `?studentId=2021001`을 붙여도 **본인 45학점만** 나온다(대조군
`2021001`은 100학점). 프롬프트로 타인 정보를 요구해도 거부한다.

![소유자 검증](docs/보고서-이미지/05-시나리오5-소유자검증.png)

같은 엔드포인트를 미인증·학생·관리자 세 주체로 호출한 결과다.

![인증 경계](docs/보고서-이미지/07-인증경계.png)

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
