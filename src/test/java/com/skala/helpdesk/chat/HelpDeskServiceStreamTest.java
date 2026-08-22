package com.skala.helpdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
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
import reactor.test.StepVerifier;

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
    void 근거_없음_응답은_전체_토큰을_누적해_무관한_sources를_제거한다() {
        Document unrelated = new Document("무관한 학칙 본문",
                Map.of("source", "한국기술교육대학교_학칙.pdf", "version", "2026-03-31"));
        HelpDeskService service = serviceStreaming(List.of(unrelated),
                "정확한 규정을 ", "확인할 수 없습니다.");

        List<StreamEvent> events = service.streamEvents("학생식당 메뉴를 알려주세요", "2021001", "no-evidence")
                .collectList().block();

        assertThat(events).containsExactly(
                new Token("정확한 규정을 "),
                new Token("확인할 수 없습니다."),
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

    // --- 스트림이 실패·취소로 끝났을 때의 메모리 (레드팀이 아니라 맥락 오염 문제) ---

    /**
     * 통합 실측에서 잡은 결함(2026-08-22) — {@code ask()}에는 있던 메모리 복구가
     * {@code streamEvents()}에는 없었다. {@code MessageChatMemoryAdvisor#before()}가 질문을
     * 즉시 저장하므로 스트림이 죽으면 답변 없는 질문이 남고, 다음 턴에 대명사로 이전 답변을
     * 가리키면 모델이 찾지 못해 "정확한 규정을 확인할 수 없습니다"로 답한다.
     */
    @Test
    void 스트림이_실패하면_답변없는_질문이_메모리에_남지_않는다() {
        List<Message> saved = new ArrayList<>();
        HelpDeskService service = serviceStreaming(saved, List.of(), new FailingStreamModel());

        assertThatThrownBy(() -> service.streamEvents("졸업 학점 요건이 어떻게 돼요?", "2021001", "s1")
                .collectList().block())
                .isInstanceOf(RuntimeException.class);

        assertThat(saved).isEmpty();
    }

    /**
     * PR #14 교차 리뷰(성우님) 지적 — 처음에는 {@code doFinally} 하나로 두 종료 신호를
     * 잡았는데, {@code doFinally}는 종료 신호를 <b>다운스트림에 전달한 뒤</b> 실행된다.
     * 그래서 {@code ChatController}의 {@code onErrorResume}이 복구가 끝나기 전에
     * {@code error → done}을 내보낼 수 있고, 클라이언트가 곧바로 같은 세션으로 다시 물으면
     * 아직 지워지지 않은 반쪽 질문을 본다. 같은 경합이 이 테스트 클래스의 간헐 실패로도
     * 드러났다.
     *
     * <p>"결국 비워진다"가 아니라 <b>오류가 아래로 내려가기 전에 이미 비워져 있다</b>를
     * 고정한다. 아래 {@code doOnError}는 서비스 안쪽 것보다 뒤에 실행되므로, 여기서 본
     * 크기가 0이면 순서가 지켜진 것이다.
     */
    @Test
    void 오류가_다운스트림에_도달하기_전에_메모리가_먼저_복구된다() {
        List<Message> saved = new ArrayList<>();
        List<Integer> sizeSeenByDownstream = new ArrayList<>();
        AtomicInteger restoreCount = new AtomicInteger();
        HelpDeskService service = serviceStreaming(saved, restoreCount, List.of(), new FailingStreamModel());

        StepVerifier.create(service.streamEvents("졸업 학점 요건이 어떻게 돼요?", "2021001", "s1")
                        .doOnError(error -> sizeSeenByDownstream.add(saved.size())))
                .expectError(RuntimeException.class)
                .verify();

        assertThat(sizeSeenByDownstream).containsExactly(0);
        // 오류 경로에서 복구가 두 번 불리지 않는다(같은 리뷰의 확인 요청).
        assertThat(restoreCount).hasValue(1);
    }

    /**
     * 타임아웃은 {@code ChatController}의 {@code .timeout()}에 걸려 있어 서비스에는 오류가
     * 아니라 <b>취소</b>로 도착한다(클라이언트 연결 끊김도 마찬가지). {@code doOnError}만
     * 붙였다면 이 테스트가 실패한다 — 실측에서 확인한 세 실패 경로 중 둘이 여기에 속한다.
     */
    @Test
    void 스트림이_취소되면_답변없는_질문이_메모리에_남지_않는다() {
        List<Message> saved = new ArrayList<>();
        HelpDeskService service = serviceStreaming(saved, List.of(), new ChunkStreamModel("졸업", " 요건은"));

        StepVerifier.create(service.streamEvents("졸업 학점 요건이 어떻게 돼요?", "2021001", "s1"), 1)
                .expectNext(new Token("졸업"))
                .thenCancel()
                .verify();

        assertThat(saved).isEmpty();
    }

    @Test
    void 이전_턴이_있는_상태에서_스트림이_실패해도_이전_턴은_보존된다() {
        List<Message> saved = new ArrayList<>();
        HelpDeskService warmup = serviceStreaming(saved, List.of(), new ChunkStreamModel("ok"));
        warmup.streamEvents("1번째 질문", "2021001", "s1").collectList().block();
        assertThat(saved).hasSize(2);

        HelpDeskService service = serviceStreaming(saved, List.of(), new FailingStreamModel());
        assertThatThrownBy(() -> service.streamEvents("2번째 질문", "2021001", "s1")
                .collectList().block())
                .isInstanceOf(RuntimeException.class);

        assertThat(saved).extracting(Message::getText).containsExactly("1번째 질문", "ok");
    }

    /** 과잉 복구 방지 — 이 단언이 없으면 "실패하면 되돌린다"가 정상 대화까지 지울 수 있다. */
    @Test
    void 정상_완료된_스트림은_메모리를_되돌리지_않는다() {
        List<Message> saved = new ArrayList<>();
        HelpDeskService service = serviceStreaming(saved, List.of(), new ChunkStreamModel("졸업 요건입니다"));

        service.streamEvents("졸업 학점 요건이 어떻게 돼요?", "2021001", "s1").collectList().block();

        assertThat(saved).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT);
    }

    // --- 테스트 헬퍼 ---

    private static HelpDeskService serviceStreaming(List<Document> documents, String... tokens) {
        return serviceStreaming(new ArrayList<>(), documents, new ChunkStreamModel(tokens));
    }

    /**
     * 메모리를 실제로 붙인다 — {@code MessageChatMemoryAdvisor}(order 200)가 질문을 언제
     * 저장하는지가 이 테스트들의 대상이라 mock으로 대체할 수 없다.
     * {@code HelpDeskServiceTest}의 {@code recordingRepository}와 같은 방식이다.
     */
    private static HelpDeskService serviceStreaming(List<Message> saved, List<Document> documents,
                                                    ChatModel model) {
        return serviceStreaming(saved, new AtomicInteger(), documents, model);
    }

    /** {@code restoreCount}는 복구(= 대화 단위 삭제) 횟수를 센다 — 중복 복구 확인용. */
    private static HelpDeskService serviceStreaming(List<Message> saved, AtomicInteger restoreCount,
                                                    List<Document> documents, ChatModel model) {
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(recordingRepository(saved, restoreCount))
                .maxMessages(20)
                .build();
        ChatClient chat = ChatClient.builder(model)
                .defaultAdvisors(
                        new DocumentInjectingAdvisor(documents),
                        MessageChatMemoryAdvisor.builder(chatMemory).order(200).build())
                .build();
        return new HelpDeskService(chat, chatMemory, null, null);
    }

    private static ChatMemoryRepository recordingRepository(List<Message> saved, AtomicInteger restoreCount) {
        return new ChatMemoryRepository() {
            @Override
            public List<String> findConversationIds() {
                return List.of();
            }

            @Override
            public List<Message> findByConversationId(String conversationId) {
                return new ArrayList<>(saved);
            }

            @Override
            public void saveAll(String conversationId, List<Message> messages) {
                saved.clear();
                saved.addAll(messages);
            }

            @Override
            public void deleteByConversationId(String conversationId) {
                restoreCount.incrementAndGet();
                saved.clear();
            }
        };
    }

    /** 스트림이 시작하자마자 실패하는 모델 — 실측 ⑴(없는 모델 주입)에 해당한다. */
    private static final class FailingStreamModel implements ChatModel {

        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("이 테스트는 스트리밍 경로만 사용한다");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.error(new RuntimeException("모델 장애 주입"));
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().build();
        }
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
