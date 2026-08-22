# B(박성우) 작업 순서

> 역할: 업무 API·Tool·보안 첫 번째 책임자
>
> 브랜치: `parksungwoo/skala-helpdesk-ai`
>
> 기준 문서: `AGENTS.md` 규약, `docs/분업-역할표.md`, `docs/검증-시나리오.md`, `docs/레드팀-체크리스트.md`

이 문서는 B가 실제로 작업할 순서를 정리한 체크리스트다. Phase 숫자만 따라가지 않고,
A의 구현을 기다리지 않고 진행할 수 있는 작업을 먼저 배치했다.

## 0. 작업 전 공통 규칙

매 작업을 시작하기 전에 다음을 확인한다.

- [ ] 현재 브랜치가 `parksungwoo/skala-helpdesk-ai`인지 확인한다.
- [ ] `git status --short`로 작업트리가 깨끗한지 확인한다.
- [ ] `main`의 최신 변경을 개인 브랜치에 반영한다.
- [ ] `rg -n "TODO\\(B" src/main/java`로 B 작업 지점을 확인한다.
- [ ] 수정할 파일의 Javadoc과 완료 기준을 먼저 읽는다.
- [ ] 상대 소유 파일을 변경해야 한다면 코드를 수정하기 전에 A에게 알린다.
- [ ] `.env`, API 키, `build/`, `.gradle/`을 커밋하지 않는다.

권장 동기화 순서:

```bash
git switch main
git pull origin main
git switch parksungwoo/skala-helpdesk-ai
git merge main
```

작업 완료 전 공통 확인:

```bash
./gradlew build
git status --short
git diff --check
```

커밋은 하나의 논리적 변경만 포함하고, 제목은 한국어 명령형 50자 이내로 작성한다.

---

## 1. Phase 1 — 로컬 환경과 스캐폴드 검증

### 목적

기능을 구현하기 전에 현재 스캐폴드가 실제 환경에서 기동하는지 확인한다. 이 단계에서는
A 소유 설정을 임의로 고치지 않는다.

### 담당 파일

- `.env.example`
- `README.md`
- `http/samples.http`
- `advisor/AuditAdvisor.java`의 기존 골격 확인

### 작업

- [x] `docker compose up -d`로 pgvector를 실행한다.
- [x] 컨테이너가 healthy인지 확인한다.
- [x] 개인 OpenAI 키를 환경변수로만 주입한다.
- [x] `./gradlew build`를 실행한다.
- [x] `./gradlew bootRun`으로 실제 기동을 확인한다.
- [x] `/actuator/health`가 `UP`인지 확인한다.
- [x] Swagger UI가 열리는지 확인한다.
- [x] `http/samples.http`의 경로가 실제 Controller 경로와 일치하는지 확인한다.
- [x] 의존성 좌표 문제가 없음을 `./gradlew build`로 확인한다.

### 완료 기준

- [x] 실제 애플리케이션이 기동된다.
- [x] API 키가 Git 상태에 나타나지 않는다.
- [x] RAG와 Tool의 TODO 응답은 아직 정상 출발선으로 인정한다.

### 2026-08-21 실행 결과

- `./gradlew build`: 성공
- pgvector: `helpdesk` 계정·DB 연결 성공
- Health: HTTP 200, `UP`
- Swagger UI: `/swagger-ui/index.html` HTTP 200
- OpenAPI: `http/samples.http`의 7개 경로·메서드와 일치
- 환경 이슈: macOS의 로컬 PostgreSQL이 5432를 사용 중이어서 Docker 포트를
  15432로 재정의했다. 재현 명령은 README에 기록했다.
- A 확인 사항: 팀 환경에서 5432 충돌 여부를 확인하고 포트를 통일한다.

### A에게 전달할 내용

- 기동 성공 여부
- pgvector 연결 여부
- Health·Swagger 결과
- A 설정에서 확인이 필요한 문제

---

## 2. Phase 4 — 학적 조회와 수강철회 Tool 구현

> A의 RAG 구현과 독립적으로 진행할 수 있으므로 B의 첫 기능 작업으로 수행한다.

### 담당 파일

- `tools/AcademicTools.java`
- `tools/RequestTools.java`
- 새로 작성할 Tool 단위 테스트

### 수정 금지 파일

- `repository/StudentRecordRepository.java`
- `repository/WithdrawalRequestRepository.java`
- `domain/*`

### 2-1. AcademicTools

- [x] `myCourses`의 Tool 설명에 언제 사용하는지와 예시 표현을 넣는다.
- [x] `gradStatus`의 Tool 설명에 언제 사용하는지와 반환 정보를 명시한다.
- [x] 학번을 모델이 생성하는 `@ToolParam`으로 받지 않도록 정리한다.
- [x] 인증된 학번은 `ToolContext`의 `studentId`에서만 꺼낸다.
- [x] 조회는 반드시 `findByIdAndOwnerId`만 사용한다.
- [x] 모델에 학번 파라미터를 노출하지 않고, 인증 학번이 없거나 잘못되면
  같은 비노출 문구로 응답한다.
- [x] 수강 과목·누적 학점 응답을 짧고 명확하게 작성한다.
- [x] 졸업 충족도 응답에 누적 학점·GPA·어학·캡스톤 상태를 포함한다.

### 2-2. RequestTools

- [x] `requestDrop` 설명에 수강철회 접수용 Tool임을 명시한다.
- [x] 즉시 철회되지 않고 지도교수 승인 후 처리됨을 Tool 설명에 포함한다.
- [x] 과목코드·과목명과 철회 사유의 `@ToolParam` 설명을 완성한다.
- [x] 학번은 `ToolContext`에서만 가져온다.
- [x] 인증된 학생의 학적이 존재하는지 `findByIdAndOwnerId`로 확인한다.
- [x] 요청한 과목이 해당 학생의 수강 과목인지 확인한다.
- [x] `WithdrawalRequestRepository#create`만 호출한다.
- [x] 결과에 신청번호와 `PENDING`·승인 대기 안내를 포함한다.
- [x] `approve` 경로를 Tool로 만들거나 노출하지 않는다.

### 2-3. 테스트

- [x] 2021001이 자신의 수강 정보 조회에 성공한다.
- [x] 2021002 인증 문맥에서 2021001의 정보가 반환되지 않는다.
- [x] 없는 인증 학번과 인증 정보 누락은 같은 비노출 응답을 받는다.
- [x] 정상 수강철회 접수는 `PENDING` 상태와 신청번호를 반환한다.
- [x] 사용자가 과목명으로 요청해도 실제 과목코드로 접수한다.
- [x] 다른 학생의 과목 또는 수강하지 않은 과목은 접수되지 않는다.
- [x] 승인 메서드는 모델의 Tool 목록에 존재하지 않는다.

### 완료 기준

- [x] 검증 시나리오 ②·④·⑤의 Tool·권한 부분을 단위·스키마 테스트로 통과한다.
- [x] 응답에서 `TODO(B, Phase 4)` 문자열이 사라진다.
- [x] A가 Tool 설명과 반환 길이를 리뷰한다.

### 2026-08-21 실행 결과

- `AcademicToolsTest` 6개, `RequestToolsTest` 8개, `ToolSchemaContractTest` 1개 통과
- Spring AI 최종 Tool 스키마: `myCourses`, `gradStatus`, `requestDrop`만 노출
- 스키마에 `studentId`·`approve`가 없음을 확인
- `./gradlew build` 성공, 애플리케이션과 pgvector 기동 성공
- 실제 모델 통합 시나리오 ②·④·⑤ 통과(2026-08-21)
  - ② 누적 100학점, `toolUsed=true`
  - ④ 알고리즘을 `CS201`로 해석해 `WD-0001/PENDING` 접수, 승인 미실행
  - ⑤ 2021002의 타인 정보 요청 거부, `toolUsed=false`
- Spring AI 2.0의 Tool Calling을 Memory(200)와 RAG(300) 뒤인 order 350으로 배치해
  JDBC 메모리가 저장하지 않는 도구 중간 메시지는 내부 이력으로 처리하고 RAG 중복 실행도 방지함

### 권장 커밋 분리

1. `학적 조회 도구를 구현한다`
2. `수강철회 승인 대기 접수를 구현한다`
3. `도구 소유자 검증 테스트를 추가한다`

---

## 3. Phase 2 심화 — 인제스트 청크 검사 API

> 선행조건: A의 Phase 2 `IngestService`가 개인 브랜치 또는 main에 반영되어야 한다.

### 담당 파일

- `web/AdminController.java`
- 관리자 청크 검사 API 테스트

### 작업

- [x] A에게 인제스트 완료와 필수 메타데이터 키를 확인한다.
- [x] `GET /api/admin/chunks?q=...&topK=5`를 실행한다.
- [x] 응답에 `source`, `docType`, `dept`, `version`, `score`, `preview`가 포함되는지 확인한다.
- [x] 검색 결과가 없으면 빈 목록을 반환한다.
- [x] `score` 또는 본문이 null이어도 500 오류가 발생하지 않게 처리한다.
- [x] `topK`가 1 미만이거나 설정 상한보다 큰 입력을 검증한다.
- [x] 진단 시 `threshold=0`으로 임계값 없이 검색할 수 있게 한다.
- [x] `preview`는 최대 160자로 제한한다.
- [x] 같은 문서를 여러 번 인제스트한 뒤 중복 청크가 누적되지 않는지 확인한다.
- [x] 졸업학점·장학금·학사운영 질문이 각각 기대 문서를 찾는지 확인한다.

### 완료 기준

- [x] 성공 로그가 아니라 실제 검색 결과로 인제스트 상태를 검증한다.
- [x] Phase 3에서 사용할 출처 메타데이터가 실제 응답에 존재한다.
- [x] Phase 7 전까지는 관리자 권한 TODO를 남기되, 보안 미완료 상태를 문서에 표시한다.

### 2026-08-21 실행 결과

- `AdminControllerTest` 9개와 전체 `./gradlew --offline clean build` 통과
- 기본값은 Advisor와 동일한 임계값 `0.3`을 적용하고, 진단 요청에서 `threshold` 재정의 가능
- 실제 적용 임계값은 `X-Applied-Similarity-Threshold` 응답 헤더로 확인
- 운영 `top-k: 5`와 진단 상한 `inspection-max-top-k: 50`을 설정으로 분리
- 실제 `threshold=0&topK=20` 요청에서 HTTP 200, 결과 20건, 적용 임계값 헤더 `0.0` 확인
- 실제 pgvector 검색의 최상위 출처 확인
  - 졸업학점 → `graduation-requirements.md`(score 0.528)
  - 장학금 → `scholarship-policy.md`(score 0.477)
  - 휴학 신청 → `학사운영에관한규칙.pdf`(score 0.531)
- 모든 검색 결과에 `source`, `docType`, `dept`, `version`, `score`, `preview` 포함
- source별 청크 수가 2·2·2·142·166·18건으로 유지되어 재인제스트 중복 없음 확인
- 검색어 누락, 숫자가 아닌 값, `topK`·`threshold` 범위 이탈은 벡터 검색 없이 HTTP 400 반환
- `/api/admin/**` 권한 적용은 계획대로 Phase 7 TODO로 유지

### PR #2 A 리뷰 반영

- Tool Calling을 order 350으로 옮긴 뒤 실제 학점 조회가 HTTP 200·`toolUsed=true`로 통과
- 같은 요청 로그에서 RAG 벡터 검색이 1회만 실행되어 도구 반복 중복 검색 제거 확인
- 시나리오 ⑤는 모델 계층만 검증된 상태로 정정하고 Phase 7 HTTP 인증 재검증을 명시
- `findByIdAndOwnerId(studentId, studentId)`가 소유자 검증 계약임을 Tool 코드에 주석으로 기록
- 같은 과목의 중복 PENDING 방지는 보호된 Repository의 원자적 변경이 필요해 별도 과제로 보류

### 권장 커밋

`인제스트 청크 검사 API를 검증한다`

---

## 4. Phase 3 — 동기 API 계약과 RAG 응답 검증

> 선행조건: A의 Phase 3 `HelpDeskService#ask`와 출처 추출이 완료되어야 한다.

### 담당 파일

- `chat/AnswerDto.java` — 공동 계약이므로 변경 전 A와 합의
- `web/ChatController.java`
- 동기 API 테스트
- Swagger 문서

### 작업

- [x] `AnswerDto(String answer, List<Source> sources, boolean toolUsed)`를 유지한다.
- [ ] `Source(String document, String version)` 구조를 A와 함께 확인한다.
- [x] `ChatRequest.question`, `sessionId`에 입력 검증을 적용한다.
- [x] Controller에서 `ChatClient`를 직접 호출하지 않는다.
- [ ] 규정 질문이 답변과 실제 출처를 반환하는지 확인한다.
- [x] 근거가 없을 때 안전한 답변과 빈 출처 목록을 반환하는지 확인한다.
- [x] Tool 질문에서 `toolUsed`가 실제 호출 결과와 일치하는지 확인한다.
- [x] Swagger에 정상·근거 없음 응답 예시를 추가한다.

### 완료 기준

- [ ] 검증 시나리오 ①을 통과한다.
- [ ] 출처 없는 규정 답변을 정상으로 처리하지 않는다.
- [ ] DTO 변경이 필요했다면 A의 합의와 리뷰가 기록돼 있다.

### 2026-08-22 API 계약 검증 결과

- `ChatControllerTest` 6개 통과
  - 정상 응답의 `answer`·`sources`·`toolUsed` 계약 확인
  - 근거 없음 응답의 안전 문구·빈 출처 확인
  - 빈 질문·빈 세션 ID·100자 초과 세션 ID를 HTTP 400으로 차단
  - 동기·SSE 요청에 같은 `ChatRequest` 검증 적용
- Swagger에 규정 근거 답변·근거 없음 응답 예시 추가
- 전체 `./gradlew clean build` 성공(총 39개 테스트 통과)
- 실제 OpenAI RAG로 시나리오 ①을 B가 직접 재현하는 작업과 `Source` 계약의 A 확인은 남아 있다.

### 권장 커밋

`동기 상담 API 계약을 검증한다`

---

## 5. Phase 5 — 메모리·학번·세션 격리 테스트

> 선행조건: A의 `ChatMemoryConfig`와 `HelpDeskService#conversationId`가 완료되어야 한다.

### 담당 범위

- 메모리 통합 테스트
- `http/samples.http`의 멀티턴 시나리오
- 검증 결과 문서

### A 소유 코드 리뷰 항목

- [x] `conversationId`가 `HelpDeskService` 한 곳에서만 만들어진다.
- [x] 형식이 `skala:{학번}:{세션}`이다.
- [x] 최대 메시지 수가 `application.yml` 설정으로 관리된다.
- [x] JDBC 메모리를 사용한다.

### 테스트

- [ ] 같은 학번·같은 세션에서 3턴 맥락이 유지된다.
- [x] 같은 학번·다른 세션에서는 이전 맥락이 보이지 않는다.
- [x] 다른 학번·같은 세션명에서도 대화가 섞이지 않는다.
- [ ] 앱 재기동 후에도 JDBC 메모리가 유지된다.
- [ ] 차단된 요청이 메모리에 저장되지 않는지는 Phase 7에서 다시 확인한다.

필수 3턴:

```text
1. 졸업 학점 요건이 어떻게 돼요?
2. 제가 지금 몇 학점이죠?
3. 그럼 저 졸업 가능해요?
```

### 완료 기준

- [ ] 검증 시나리오 ③을 통과한다.
- [x] 학번 또는 세션을 바꾼 테스트에서 정보가 섞이지 않는다.
- [ ] A에게 실패 재현 요청과 로그를 전달할 수 있다.

### 2026-08-22 메모리 계약 검증 결과

- `HelpDeskServiceMemoryTest` 5개 통과
  - `skala:{학번}:{세션}` 대화 ID 규칙 확인
  - 같은 학번의 다른 세션과 다른 학번의 같은 세션명 격리 확인
  - `MessageWindowChatMemory` 최근 20개 메시지 유지 및 대상 이력만 삭제되는지 확인
- 이 테스트는 외부 인프라 없이 격리 계약을 검증하기 위해 인메모리 저장소를 사용한다.
- 실제 OpenAI 3턴 시나리오 ③와 PostgreSQL 재기동 후 유지는 API 키와 Docker가 있는 통합 환경에서 다시 검증한다.

### 권장 커밋

`대화 메모리 격리 테스트를 추가한다`

---

## 6. Phase 6 — SSE Controller 완성

> 선행조건: A의 `HelpDeskService#stream`과 요청 단위 출처 전달 방식이 결정되어야 한다.

### 담당 파일

- `web/ChatController.java`
- `http/samples.http`
- SSE Controller 테스트

### A와 먼저 합의할 것

- [ ] 스트리밍 종료 시 해당 요청의 출처를 어떻게 전달할지 정한다.
- [ ] 싱글턴 필드 `lastSources`는 사용하지 않는다.
- [ ] 동시 요청에서도 출처가 섞이지 않는 요청 단위 구조를 사용한다.

### 작업

- [ ] `token` 이벤트로 모델의 텍스트 조각을 전달한다.
- [ ] 스트림 마지막에 실제 `sources` 이벤트를 전달한다.
- [ ] 필요하면 정상 종료를 알리는 `done` 이벤트를 추가한다.
- [ ] 60초 타임아웃을 유지한다.
- [ ] 사용자가 연결을 끊으면 모델 스트림도 취소되는지 확인한다.
- [ ] 스트리밍 오류 응답 방식을 정한다.
- [ ] 같은 시각에 두 요청을 보내 출처·세션이 섞이지 않는지 확인한다.
- [ ] 동기 API와 SSE가 동일한 사용자·세션 식별 규칙을 사용한다.

### 완료 기준

- [ ] SSE가 토큰을 순차적으로 전달한다.
- [ ] 마지막 출처가 빈 자리표시자 `[]`가 아니라 실제 검색 출처다.
- [ ] 동시 요청·취소·타임아웃 테스트를 통과한다.
- [ ] A가 ChatClient·RAG 연결 관점에서 리뷰한다.

### 권장 커밋

`SSE 응답에 출처와 종료 이벤트를 추가한다`

---

## 7. Phase 7 — 인증·인가·감사·레드팀

### 담당 파일

- `web/SecurityConfig.java`
- `web/ChatController.java`
- `web/AdminController.java`
- `advisor/AuditAdvisor.java`
- 필요 시 새 Tool 감사 Aspect
- `docs/레드팀-체크리스트.md`
- `http/samples.http`

### 7-1. 인증과 인가

- [ ] `permitAll`을 제거하고 역할 기반 인가를 적용한다.
- [ ] 학생과 학사팀 관리자 역할을 분리한다.
- [ ] `/api/admin/**`는 ADMIN만 접근할 수 있게 한다.
- [ ] `AdminController`에 `@PreAuthorize("hasRole('ADMIN')")`를 적용한다.
- [ ] Chat API의 학번을 요청 파라미터가 아니라 인증 `Principal`에서 가져온다.
- [ ] 클라이언트가 학번을 위조해도 인증 주체가 바뀌지 않게 한다.
- [ ] 일반 학생의 관리자 API 접근이 403인지 확인한다.

### 7-2. 승인 게이트

- [x] 수강철회 접수는 Tool에서 `PENDING`까지만 수행한다.
- [x] 승인은 `AdminController`에서만 실행된다.
- [x] 승인 메서드가 AI Tool 목록에 없는지 다시 확인한다.
- [ ] 학생이 관리자라고 주장해도 승인되지 않는지 테스트한다.

### 7-3. 감사 로그

- [ ] 채팅 호출 성공·실패·소요시간을 `AI_TOOL_AUDIT`에 기록한다.
- [ ] Tool 호출 성공·실패를 누락 없이 기록할 방식을 결정한다.
- [ ] 필요하면 별도 AOP Aspect를 추가한다.
- [ ] 학번을 마스킹한다.
- [ ] 성적·질문 원문·시스템 프롬프트를 로그에 남기지 않는다.
- [ ] 예외 메시지에 민감정보가 섞이지 않는지 확인한다.
- [ ] 실패 요청도 traceId로 추적할 수 있게 한다.

### 7-4. SafeGuard 교차 리뷰

- [x] A의 SafeGuard가 Memory보다 앞(order 100)에 있는지 확인한다.
- [x] 차단된 입력이 대화 이력에 남지 않는지 확인한다.
- [x] 긴 입력과 개인정보 패턴이 차단되는지 확인한다.

### 7-5. 레드팀 10종

- [ ] 지시 무시
- [ ] 시스템 프롬프트 노출
- [ ] 관리자 사칭
- [ ] 학번 권한 우회
- [ ] 없는 Tool 실행 유도
- [ ] 전체 학생 데이터 유출
- [ ] 문서 기반 간접 인젝션
- [ ] Tool 반복 호출
- [ ] 개인정보 저장·노출
- [ ] 긴 입력 비용 공격

뚫린 경로는 프롬프트 문구만 바꾸지 않고 코드·권한·Tool 경계에서 수정한 뒤 재검증한다.

### 완료 기준

- [ ] 검증 시나리오 ④·⑤·⑥을 통과한다.
- [ ] 레드팀 10종의 요청·응답·로그 증거가 문서에 남는다.
- [ ] Tool 성공과 실패가 모두 감사 로그에 남는다.
- [ ] A가 Advisor 순서와 RAG 정보 노출 관점에서 리뷰한다.

### 권장 커밋 분리

1. `학생과 관리자 API 권한을 분리한다`
2. `채팅과 도구 호출 감사 로그를 추가한다`
3. `레드팀 공격 경로를 차단한다`

---

## 8. Phase 8 — Golden Set·성능·폴백 검증

> A가 토큰·지연 지표와 폴백을 구현하고, B가 실행·평가·결과 기록을 담당한다.

### 담당 파일

- `eval/GoldenSet.java`
- 새 Golden Set 실행 테스트
- 성능·장애 검증 결과 문서
- `docs/검증-시나리오.md`

### 8-1. Golden Set

- [x] 학사운영규정 질문을 포함한다.
- [x] 졸업요건 질문을 포함한다.
- [x] 장학금 질문을 포함한다.
- [x] 근거 없음 질문을 포함한다.
- [x] 총 20개 질문과 기대 `source`를 작성한다.
- [x] `HelpDeskService#ask`를 호출하는 실행기를 작성한다.
- [x] 실제 출처가 기대 문서를 포함하는지 검증한다.
- [x] 실제 모델 테스트는 기본 테스트와 분리한다.

### 8-2. 성능과 Tool 품질

- [x] 비스트리밍 P95 응답시간을 기록한다.
- [x] Tool 호출 성공률과 오류율을 기록한다.
- [x] 규정 질문의 출처 적중률을 기록한다.
- [x] 근거 없음 질문이 출처를 만들어내지 않는지 확인한다.
- [x] `/actuator/metrics/ai.tokens`가 쌓이는지 확인한다.
- [x] `/actuator/metrics/ai.latency`가 쌓이는지 확인한다.

### 8-3. 장애와 폴백

- [x] A에게 주 모델 장애 주입 방법을 전달받는다.
- [x] 주 모델 장애 시 폴백 응답을 확인한다.
- [x] 폴백도 실패했을 때 안전한 오류 문구와 traceId가 반환되는지 확인한다.
- [x] 장애가 앱 전체 기동을 막지 않는지 확인한다.

### 완료 기준

- [x] 검증 시나리오 ⑦을 통과한다.
- [x] Golden Set 20종 결과가 기록돼 있다.
- [x] P95·Tool 성공률·오류율·출처 적중률이 기록돼 있다.
- [x] A의 계측·폴백 구현을 B가 직접 실행해 검증했다.

### 2026-08-22 구현 결과

- 학사 5·졸업 5·장학 4·Tool 3·근거 없음 3의 총 20문항을 작성했다.
- 답변 전체 일치 대신 핵심 사실·기대 출처·Tool 사용·근거 없음 정책을 검사한다.
- `./gradlew goldenSetTest`를 별도 태스크로 분리하고 실행 결과를
  `build/reports/golden-set/results.md`에 생성하도록 했다.
- 실행기는 전체 통과율, 출처 적중률, Tool 성공률·오류율, 근거 없음 통과율,
  비스트리밍 P95를 집계한다.
- 2026-08-22 B 첫 실측은 전체 75.0%·근거 없음 33.3%로 실패했다. 기대값·질문
  표현·무관 출처 후처리·고정 세션 재사용을 교정한 후 재평가는 20/20이다.
- 최종 실측: 전체·필수 출처·Tool·근거 없음 모두 100.0%, Tool 오류율
  0.0%, P95 2,539ms, 호출 오류 0건으로 `goldenSetTest`가 통과했다.
- 정상 통합 실행에서 `ai.tokens` 8,562, `ai.latency` 6건을 확인했다.
- 주 모델만 장애 주입 시 HTTP 200·폴백 성공·메모리 2건을, 주·폴백 모두
  장애 시 HTTP 500·안전 문구·traceId·Health `UP`을 확인했다.
- 주·폴백 모두 실패한 요청의 사용자 메시지 1건이 메모리에 남는 현행 동작을
  추가로 재현했다.
- 상세 실행법과 합격 기준: `docs/골든셋-평가.md`
- AI 호출이 최종 실패할 때 HTTP 500 응답에는 안전 문구와 UUID 형식 `traceId`만 반환하고,
  내부 예외 메시지가 노출되지 않는 Controller 계약 테스트를 추가했다. 실제 주·폴백 모델
  동시 장애 주입은 통합 환경 검증 항목으로 유지한다.

### 권장 커밋 분리

1. `학사 안내 골든셋을 추가한다`
2. `품질과 성능 평가 결과를 기록한다`
3. `모델 폴백 시나리오를 검증한다`

---

## 9. 최종 통합 검증과 PR

### 두 사람이 각각 실행할 시나리오

- [x] ① 졸업 학점 질문 — RAG 근거와 출처
- [x] ② 본인 학점 질문 — Tool과 소유자 검증
- [x] ③ 졸업 가능 여부 — Memory + RAG + Tool
- [x] ④ 수강철회 — 승인 대기와 신청번호
- [x] ⑤ 다른 학번 조회 — 정보 미노출
- [x] ⑥ 시스템 프롬프트 공개 요청 — 차단·메모리 미저장
- [x] ⑦ 모델 장애 — 폴백 응답

### 최종 확인

- [ ] `./gradlew build`가 통과한다.
- [x] 실제 API 키로 필요한 통합 테스트를 수행한다.
- [ ] `.env`, 개인정보, 학번·성적 원문 로그가 커밋에 없다.
- [ ] Swagger와 `http/samples.http`가 실제 API와 일치한다.
- [ ] `docs/검증-시나리오.md`에 실행자와 결과를 기록한다.
- [ ] `docs/레드팀-체크리스트.md`에 10종 결과를 기록한다.
- [ ] A가 B 담당 기능을 리뷰하고 직접 실행한다.
- [ ] B가 A 담당 기능을 리뷰하고 직접 실행한다.

### PR 본문 필수 내용

```text
왜:
- 이 변경이 필요한 이유

무엇을:
- 변경한 기능과 파일

어떻게 검증했는지:
- 실행한 테스트 명령
- 검증 시나리오 결과
- 필요한 로그 또는 응답 예시
```

main에는 직접 커밋하지 않고 PR로만 반영한다.

---

## 10. 작업 의존성 요약

| B 작업 | 선행조건 | 기다리는 동안 할 수 있는 작업 |
|---|---|---|
| Phase 1 환경 검증 | 없음 | 즉시 시작 |
| Phase 4 Tool | Phase 1 기동 또는 단위 컴파일 | 즉시 시작 가능 |
| Phase 2 청크 검사 | A의 Phase 2 인제스트 | Tool 테스트 작성 |
| Phase 3 동기 API | A의 Phase 3 RAG 답변 | Swagger·요청 검증 준비 |
| Phase 5 격리 테스트 | A의 Phase 5 메모리 | Phase 4 권한 테스트 |
| Phase 6 SSE | A의 스트림·출처 계약 | Controller 테스트 골격 작성 |
| Phase 7 보안·감사 | Phase 4·5·6 기본 흐름 | Security 테스트 준비 |
| Phase 8 평가 | A의 계측·폴백 | Golden Set 질문 작성 |

실제 권장 순서는 다음과 같다.

```text
환경 검증
→ Phase 4 Tool
→ Phase 2 청크 검사
→ Phase 3 동기 API
→ Phase 5 격리 테스트
→ Phase 6 SSE
→ Phase 7 보안·감사·레드팀
→ Phase 8 평가·폴백
→ 최종 통합 검증과 PR
```
