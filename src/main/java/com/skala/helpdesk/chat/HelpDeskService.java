package com.skala.helpdesk.chat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import com.skala.helpdesk.chat.AnswerDto.Source;

import reactor.core.publisher.Flux;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 3(p.316)·Phase 5(p.319)·Phase 6(p.320).
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

    private final ChatClient chat;
    private final ChatMemory chatMemory;

    public HelpDeskService(ChatClient helpDeskClient, ChatMemory chatMemory) {
        this.chat = helpDeskClient;
        this.chatMemory = chatMemory;
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
     * TODO(A, Phase 3): {@code chat.prompt()...call().chatClientResponse()}로 호출하고
     * {@link #sourcesFrom}으로 출처를 뽑아 {@link AnswerDto}를 만든다. 근거 문서가 없으면
     * (sources가 비어 있으면) 규정을 지어내지 않았는지 답변 문구도 함께 확인한다.
     */
    public AnswerDto ask(String question, String studentId, String sessionId) {
        AtomicBoolean toolUsed = new AtomicBoolean(false);

        ChatClientResponse response = chat.prompt().user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId(studentId, sessionId)))
                .toolContext(Map.of("studentId", studentId, "toolUsed", toolUsed))
                .call().chatClientResponse();

        // TODO(A, Phase 3): 아래 두 줄이 실제 답변·출처 추출이다 — 지금은 뼈대만 있다.
        String answer = response.chatResponse().getResult().getOutput().getText();
        return new AnswerDto(answer, sourcesFrom(response), toolUsed.get());
    }

    /**
     * TODO(A, Phase 6): SSE용 토큰 스트림. {@code chat.prompt()...stream().content()}를
     * 반환한다. 마지막 출처 이벤트는 {@code ChatController}가 별도로 붙인다(p.320 코드 참고
     * — 스트림 완료 후 {@code lastSources}를 호출해 마지막 이벤트로 내보낸다).
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
