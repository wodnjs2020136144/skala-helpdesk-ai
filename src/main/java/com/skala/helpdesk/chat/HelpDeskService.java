package com.skala.helpdesk.chat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import com.skala.helpdesk.chat.AnswerDto.Source;
import com.skala.helpdesk.config.AiOpsProperties;
import com.skala.helpdesk.tools.BoundedToolCallingManager;
import com.skala.helpdesk.tools.ToolCallLimitExceededException;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import reactor.core.publisher.Flux;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 3(p.316)·Phase 5(p.319)·Phase 6(p.320)·Phase 8.
 *
 * <p>{@code conversationId}를 <b>이 한 곳에서만</b> 만든다. 규칙이 흩어지면 남의 대화가
 * 섞이는 사고가 난다 — 메모리에서 가장 흔한 버그이고 가장 늦게 발견된다(교안 함정).
 * 학번은 {@code toolContext}로 넘겨 {@code AcademicTools}·{@code RequestTools}가 소유자
 * 검증에 쓸 수 있게 한다.
 *
 * <p>참고: {@code day3-consult-agent/service/ConsultService.java}, 교안 Phase 3(p.316)·
 * Phase 5(p.319)·Phase 6(p.320) 코드.
 *
 * <p>완료 기준: 멀티턴 — "그럼 저 졸업 가능해요?" 같은 대명사 후속 질문이 이전 턴을
 * 참조해 응답한다(검증 시나리오 ③). 새 세션에서 물으면 맥락이 없어 되묻는다(세션 격리).
 */
@Service
public class HelpDeskService {

    private static final Logger log = LoggerFactory.getLogger(HelpDeskService.class);

    private final ChatClient chat;
    private final ChatMemory chatMemory;
    private final AiOpsProperties aiOps;
    private final MeterRegistry registry;

    public HelpDeskService(ChatClient helpDeskClient, ChatMemory chatMemory,
                           AiOpsProperties aiOps, MeterRegistry registry) {
        this.chat = helpDeskClient;
        this.chatMemory = chatMemory;
        this.aiOps = aiOps;
        this.registry = registry;
    }

    /**
     * 대화 ID 규칙 — 테넌트·학번·세션을 합쳐 하나로 만든다. 이 메서드 밖에서 조합하지 않는다
     * (Phase 5, p.319). 지금은 단일 테넌트("skala")만 다루므로 상수로 둔다 — 멀티 테넌트로
     * 확장하면 파라미터로 받는다.
     */
    public String conversationId(String studentId, String sessionId) {
        return "skala:%s:%s".formatted(studentId, sessionId);
    }

    /**
     * 검증 완료(2026-08-22, 실제 OpenAI + pgvector) — 시나리오 ①②⑥ 실측 확인,
     * {@code docs/검증-시나리오.md} 참고. 근거 문서가 없으면(sources가 비어 있으면) "정확한
     * 규정을 확인할 수 없습니다" 류로 답하고 규정을 지어내지 않음을 확인했다.
     *
     * <p>Phase 8 — 주 모델 호출이 실패하면 {@code helpdesk.ai.fallback.model}로 한 번만
     * 재시도한다(검증 시나리오 ⑦). {@code DefaultAroundAdvisorChain}은 advisor를
     * {@code Deque}에서 꺼내 쓰는 소모성 체인이라 Advisor 내부에서 재시도할 수 없다 —
     * 그래서 여기 서비스 계층에서 {@code chat.prompt()}를 다시 호출해 새 체인을 만든다.
     * 재시도도 같은 {@code conversationId}·{@code toolContext}를 쓴다(재시도가 별개 대화로
     * 갈라지면 안 된다).
     *
     * <p><b>메모리 중복 저장 방지</b> — {@code MessageChatMemoryAdvisor#before()}는 모델
     * 호출 성공 여부와 무관하게 사용자 메시지를 <i>즉시</i> 저장한다(실측 확인, Spring AI
     * 2.0 소스). 그래서 1차 호출이 실패해도 질문은 이미 메모리에 한 번 저장된 뒤다 —
     * {@link #restoreMemory}로 호출 직전 스냅샷으로 되돌려야 한다. <b>세 경로 모두</b>에서
     * 되돌린다: 폴백 재시도 전, 폴백이 꺼져 있어 바로 전파할 때, 폴백까지 실패했을 때
     * (PR #5 교차 리뷰에서 폴백 최종 실패 경로의 누락을 지적받아 세 번째를 추가했다 — 그대로
     * 두면 답변 없는 질문 1개가 메모리에 남아 다음 턴 맥락을 오염시킨다).
     *
     * <p><b>도구 호출 상한 초과는 폴백하지 않는다</b> — {@link ToolCallLimitExceededException}
     * 은 모델 장애가 아니라 우리 안전장치가 의도적으로 발동한 것이라, 폴백이 새 요청 단위
     * 카운터로 도구를 처음부터 다시 호출하게 두면 상한이 사실상 두 배가 된다.
     */
    public AnswerDto ask(String question, String studentId, String sessionId) {
        String conversationId = conversationId(studentId, sessionId);
        List<Message> beforeAttempt = chatMemory.get(conversationId);

        try {
            return askWith(null, question, studentId, sessionId);
        } catch (ToolCallLimitExceededException limitExceeded) {
            restoreMemory(conversationId, beforeAttempt);
            throw limitExceeded;
        } catch (RuntimeException primaryFailure) {
            if (!aiOps.fallback().enabled()) {
                restoreMemory(conversationId, beforeAttempt);
                throw primaryFailure;
            }
            log.warn("주 모델 호출 실패 — 폴백 모델로 재시도합니다 model={} cause={}",
                    aiOps.fallback().model(), primaryFailure.getMessage());
            restoreMemory(conversationId, beforeAttempt);
            try {
                AnswerDto fallbackAnswer = askWith(aiOps.fallback().model(), question, studentId, sessionId);
                registry.counter("ai.fallback", Tags.of("outcome", "success")).increment();
                return fallbackAnswer;
            } catch (RuntimeException fallbackFailure) {
                restoreMemory(conversationId, beforeAttempt);
                registry.counter("ai.fallback", Tags.of("outcome", "failed")).increment();
                throw fallbackFailure;
            }
        }
    }

    /**
     * 실패한 시도가 남긴 사용자 메시지를 지우고 호출 직전 상태로 되돌린다. 세 실패 경로
     * ({@link #ask} 참고) 모두 이 메서드 하나로 복구해 빠짐을 막는다.
     */
    private void restoreMemory(String conversationId, List<Message> beforeAttempt) {
        chatMemory.clear(conversationId);
        chatMemory.add(conversationId, beforeAttempt);
    }

    private AnswerDto askWith(String modelOverride, String question, String studentId, String sessionId) {
        AtomicBoolean toolUsed = new AtomicBoolean(false);
        AtomicInteger toolCallCounter = new AtomicInteger(0);

        ChatClient.ChatClientRequestSpec request = chat.prompt().user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId(studentId, sessionId)))
                .toolContext(Map.of(
                        "studentId", studentId,
                        "toolUsed", toolUsed,
                        BoundedToolCallingManager.TOOL_CALL_COUNTER, toolCallCounter));
        if (modelOverride != null) {
            request = request.options(ChatOptions.builder().model(modelOverride));
        }

        ChatClientResponse response = request.call().chatClientResponse();

        String answer = response.chatResponse().getResult().getOutput().getText();
        return new AnswerDto(answer, sourcesFrom(response), toolUsed.get());
    }

    /**
     * TODO(A, Phase 6): SSE용 토큰 스트림. {@code chat.prompt()...stream().content()}를
     * 반환한다. 마지막 출처 이벤트는 {@code ChatController}가 별도로 붙인다(p.320 코드 참고
     * — 스트림 완료 후 {@code lastSources}를 호출해 마지막 이벤트로 내보낸다).
     *
     * <p>Phase 8 폴백은 이 메서드에는 아직 없다 — {@link #ask}처럼 실패를 감지해 재시도하려면
     * 이미 내보낸 토큰과 겹치지 않게 스트림을 다시 시작하는 방법을 {@code ChatController}의
     * sources 이벤트 계약(B)과 함께 정해야 한다. Phase 6 작업 때 같이 처리한다.
     */
    public Flux<String> stream(String question, String studentId, String sessionId) {
        AtomicBoolean toolUsed = new AtomicBoolean(false);
        return chat.prompt().user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId(studentId, sessionId)))
                .toolContext(Map.of("studentId", studentId, "toolUsed", toolUsed))
                .stream().content();
    }

    public List<String> history(String studentId, String sessionId) {
        return chatMemory.get(conversationId(studentId, sessionId)).stream()
                .map(m -> m.getMessageType() + ": " + m.getText())
                .toList();
    }

    public void clearHistory(String studentId, String sessionId) {
        chatMemory.clear(conversationId(studentId, sessionId));
    }

    /** {@code QuestionAnswerAdvisor}의 컨텍스트 키에서 출처를 뽑는다(Phase 3, p.316). */
    @SuppressWarnings("unchecked")
    protected List<Source> sourcesFrom(ChatClientResponse response) {
        Object raw = response.context().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if (!(raw instanceof List<?> docs)) {
            return List.of();
        }
        return ((List<Document>) docs).stream()
                .map(d -> new Source(
                        String.valueOf(d.getMetadata().get("source")),
                        String.valueOf(d.getMetadata().get("version"))))
                .distinct()
                .toList();
    }
}
