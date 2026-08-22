package com.skala.helpdesk.chat;

import java.util.List;

import com.skala.helpdesk.chat.AnswerDto.Source;

/**
 * 담당(파일 소유): A / B가 SSE 직렬화 관점을 검토한다(docs/분업-역할표.md).
 *
 * <p>Phase 6 — {@code HelpDeskService.streamEvents()}가 내보내는 요청 단위 스트림 이벤트.
 * PR #4·#6 코멘트에서 A·B가 합의한 계약: 정상 경로는 {@code token* → sources → done} 순서로
 * 나간다. {@code AnswerDto}는 동기 API 계약이라 바꾸지 않는다 — 이 타입은 SSE 전용이다.
 *
 * <p>오류 이벤트({@code error})는 여기 없다 — {@code TraceIdFilter}가 MDC에 심는 traceId는
 * 리액티브 스레드 경계를 넘지 못해 이 서비스 계층에서는 만들 수 없다. 이 스트림은 오류를
 * {@code onError}로 그대로 전파하고, HTTP 스레드에서 traceId를 쥔 {@code ChatController}가
 * {@code error → done}으로 직렬화한다(합의 사항, PR 본문 참고).
 *
 * <p>취소 시에는 어떤 이벤트도 보내지 않는다 — 클라이언트 연결이 이미 끊긴 뒤라 SSE 전송을
 * 보장할 수 없다. 대신 {@code TokenMeterAdvisor}의 {@code ai.latency{outcome=cancelled}}
 * 지표로 서버 쪽에서 확인한다(Phase 8, 이미 구현됨).
 */
public sealed interface StreamEvent {

    /** 모델이 생성한 텍스트 조각. 도구 호출 중간 청크처럼 텍스트가 없는 청크는 내보내지 않는다. */
    record Token(String text) implements StreamEvent {
    }

    /**
     * RAG 근거 문서. {@code QuestionAnswerAdvisor}는 {@code BaseAdvisor}라 스트림 시작
     * 전에 이미 출처를 확정한다 — 사실상 첫 청크부터 알 수 있지만, 동기 API와의 SSE 응답
     * 구조 일관성을 우선한 B의 판단에 따라 {@code done} 바로 앞으로 보낸다.
     */
    record Sources(List<Source> sources) implements StreamEvent {
    }

    /** 스트림 정상 종료. {@code toolUsed}는 동기 {@link AnswerDto#toolUsed()}와 같은 의미다. */
    record Done(boolean toolUsed) implements StreamEvent {
    }
}
