package com.skala.helpdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;

import com.skala.helpdesk.chat.AnswerDto.Source;
import com.skala.helpdesk.chat.StreamEvent.Done;
import com.skala.helpdesk.chat.StreamEvent.Sources;
import com.skala.helpdesk.chat.StreamEvent.Token;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 담당: A. Phase 6 — {@code HelpDeskService.streamEvents()}가 PR #4·#6에서 A·B가 합의한
 * SSE 계약({@code token* → sources → done})을 지키는지, 실제 OpenAI 호출 없이 검증한다.
 * 실제 pgvector·OpenAI를 쓰는 통합 검증(인증 붙은 뒤 curl 재현)은 이 테스트로 대체되지
 * 않는다 — {@code docs/검증-시나리오.md}에 별도로 기록한다.
 */
class HelpDeskServiceStreamTest {

    @Test
    void 스트림은_token들_다음에_sources_done_순서로_끝난다() {
        Document doc = new Document("본문", Map.of("source", "graduation-requirements.md", "version", "2026-08"));
        HelpDeskService service = serviceStreaming(List.of(doc), "졸업", " 요건입니다");

        List<StreamEvent> events = service.streamEvents("졸업 학점 요건이 어떻게 돼요?", "2021001", "s1")
                .collectList().block();

        assertThat(events).containsExactly(
                new Token("졸업"),
                new Token(" 요건입니다"),
                new Sources(List.of(new Source("graduation-requirements.md", "2026-08"))),
                new Done(false));
    }

    @Test
    void usage_전용_마지막_청크처럼_텍스트가_없는_청크는_token으로_새지_않는다() {
        // ChunkStreamModel이 마지막에 항상 결과 없는(usage 전용) 청크를 하나 더 붙인다 —
        // 이 청크가 빈 문자열 Token으로 새지 않는지 확인한다.
        HelpDeskService service = serviceStreaming(List.of(), "안녕하세요");

        List<StreamEvent> events = service.streamEvents("질문", "2021001", "s1").collectList().block();

        assertThat(events).containsExactly(
                new Token("안녕하세요"),
                new Sources(List.of()),
                new Done(false));
    }

    @Test
    void 서로_다른_두_스트림_요청은_출처가_섞이지_않는다() {
        Document docA = new Document("본문A", Map.of("source", "a.md", "version", "v1"));
        Document docB = new Document("본문B", Map.of("source", "b.md", "version", "v1"));
        HelpDeskService serviceA = serviceStreaming(List.of(docA), "A토큰");
        HelpDeskService serviceB = serviceStreaming(List.of(docB), "B토큰");

        Mono<List<StreamEvent>> resultA = serviceA.streamEvents("질문 A", "2021001", "s1").collectList();
        Mono<List<StreamEvent>> resultB = serviceB.streamEvents("질문 B", "2021002", "s1").collectList();

        List<StreamEvent> eventsA = resultA.block();
        List<StreamEvent> eventsB = resultB.block();

        assertThat(eventsA).containsExactly(
                new Token("A토큰"), new Sources(List.of(new Source("a.md", "v1"))), new Done(false));
        assertThat(eventsB).containsExactly(
                new Token("B토큰"), new Sources(List.of(new Source("b.md", "v1"))), new Done(false));
    }

    // --- 테스트 헬퍼 ---

    private static HelpDeskService serviceStreaming(List<Document> documents, String... tokens) {
        ChatModel model = new ChunkStreamModel(tokens);
        ChatClient chat = ChatClient.builder(model)
                .defaultAdvisors(new DocumentInjectingAdvisor(documents))
                .build();
        return new HelpDeskService(chat, null, null, null);
    }

    /**
     * {@code QuestionAnswerAdvisor}를 실제 VectorStore 없이 흉내 낸다 — {@code BaseAdvisor}라
     * 스트림 시작 전 {@code before()}에서 한 번만 문서를 컨텍스트에 채운다(실제 동작과 동일).
     */
    private static final class DocumentInjectingAdvisor implements BaseAdvisor {

        private final List<Document> documents;

        DocumentInjectingAdvisor(List<Document> documents) {
            this.documents = documents;
        }

        @Override
        public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
            return request.mutate().context(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS, documents).build();
        }

        @Override
        public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
            return response;
        }

        @Override
        public String getName() {
            return "docInjector";
        }

        @Override
        public int getOrder() {
            return 300;
        }
    }

    /** 토큰마다 청크 하나씩 흘리고, OpenAI 스트리밍의 usage 전용 마지막 청크를 재현해 덧붙인다. */
    private static final class ChunkStreamModel implements ChatModel {

        private final List<String> tokens;

        ChunkStreamModel(String... tokens) {
            this.tokens = List.of(tokens);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("이 테스트는 스트리밍 경로만 사용한다");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            List<ChatResponse> chunks = new ArrayList<>();
            for (String token : tokens) {
                chunks.add(new ChatResponse(List.of(new Generation(new AssistantMessage(token)))));
            }
            chunks.add(new ChatResponse(List.of())); // usage 전용 — getResult()가 null
            return Flux.fromIterable(chunks);
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().build();
        }
    }
}
