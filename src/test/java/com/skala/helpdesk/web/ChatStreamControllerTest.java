package com.skala.helpdesk.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.http.codec.ServerSentEvent;

import com.skala.helpdesk.chat.AnswerDto.Source;
import com.skala.helpdesk.chat.HelpDeskService;
import com.skala.helpdesk.chat.StreamEvent;
import com.skala.helpdesk.chat.StreamEvent.Done;
import com.skala.helpdesk.chat.StreamEvent.Sources;
import com.skala.helpdesk.chat.StreamEvent.Token;
import com.skala.helpdesk.config.ChatProperties;
import com.skala.helpdesk.web.ChatController.ChatRequest;
import com.skala.helpdesk.web.ChatController.StreamDone;
import com.skala.helpdesk.web.ChatController.StreamError;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ChatStreamControllerTest {

    private static final Duration STREAM_TIMEOUT = Duration.ofSeconds(60);

    @Mock
    private HelpDeskService helpDesk;

    private ChatController controller;

    @BeforeEach
    void setUp() {
        controller = new ChatController(helpDesk, new ChatProperties(STREAM_TIMEOUT));
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void token_sources_done을_계약_순서대로_직렬화한다() {
        List<Source> sources = List.of(new Source("graduation-requirements.md", "2026-08"));
        when(helpDesk.streamEvents("졸업 요건 알려줘", "2021001", "s1"))
                .thenReturn(Flux.just(new Token("졸업"), new Token(" 요건"),
                        new Sources(sources), new Done(true)));

        List<ServerSentEvent<Object>> events = controller.stream(
                        new ChatRequest("졸업 요건 알려줘", "s1"), principal("2021001"))
                .collectList().block(Duration.ofSeconds(1));

        assertThat(events).extracting(ServerSentEvent::event)
                .containsExactly("token", "token", "sources", "done");
        assertThat(events).extracting(ServerSentEvent::data)
                .containsExactly("졸업", " 요건", sources, new StreamDone(true));
        verify(helpDesk).streamEvents("졸업 요건 알려줘", "2021001", "s1");
    }

    @Test
    void 인증_사용자와_세션을_서비스에_그대로_전달한다() {
        when(helpDesk.streamEvents("제 학점 알려줘", "2021002", "student-session"))
                .thenReturn(Flux.just(new Sources(List.of()), new Done(false)));

        controller.stream(new ChatRequest("제 학점 알려줘", "student-session"),
                        principal("2021002"))
                .collectList().block(Duration.ofSeconds(1));

        verify(helpDesk).streamEvents("제 학점 알려줘", "2021002", "student-session");
    }

    @Test
    void 동시에_요청해도_각_요청의_출처와_token이_섞이지_않는다() {
        Source graduationSource = new Source("graduation-requirements.md", "2026-08");
        Source scholarshipSource = new Source("scholarship-policy.md", "2026-08");
        when(helpDesk.streamEvents("졸업 질문", "2021001", "graduation"))
                .thenReturn(Flux.just(new Token("졸업"), new Sources(List.of(graduationSource)), new Done(false)));
        when(helpDesk.streamEvents("장학 질문", "2021001", "scholarship"))
                .thenReturn(Flux.just(new Token("장학"), new Sources(List.of(scholarshipSource)), new Done(false)));

        Mono<List<Object>> graduation = eventData(
                controller.stream(new ChatRequest("졸업 질문", "graduation"), principal("2021001")));
        Mono<List<Object>> scholarship = eventData(
                controller.stream(new ChatRequest("장학 질문", "scholarship"), principal("2021001")));

        var result = Mono.zip(graduation, scholarship).block(Duration.ofSeconds(1));

        assertThat(result).isNotNull();
        assertThat(result.getT1()).containsExactly(
                "졸업", List.of(graduationSource), new StreamDone(false));
        assertThat(result.getT2()).containsExactly(
                "장학", List.of(scholarshipSource), new StreamDone(false));
    }

    @Test
    void 사용자가_연결을_끊으면_서비스_스트림도_취소된다() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        when(helpDesk.streamEvents("긴 답변", "2021001", "cancel"))
                .thenReturn(Flux.<StreamEvent>never().doOnCancel(() -> cancelled.set(true)));

        Disposable subscription = controller.stream(
                        new ChatRequest("긴 답변", "cancel"), principal("2021001"))
                .subscribe();
        subscription.dispose();

        assertThat(cancelled).isTrue();
    }

    @Test
    void 설정된_시간을_넘기면_안전한_error와_done으로_종료한다() {
        when(helpDesk.streamEvents("응답 없는 질문", "2021001", "timeout"))
                .thenReturn(Flux.<StreamEvent>never());

        StepVerifier.withVirtualTime(() -> controller.stream(
                        new ChatRequest("응답 없는 질문", "timeout"), principal("2021001")))
                .expectSubscription()
                .thenAwait(STREAM_TIMEOUT)
                .assertNext(event -> {
                    assertThat(event.event()).isEqualTo("error");
                    assertThat(event.data()).isInstanceOf(StreamError.class);
                })
                .expectNextMatches(event -> "done".equals(event.event())
                        && new StreamDone(false).equals(event.data()))
                .verifyComplete();
    }

    @Test
    void 모델_오류는_내부_메시지를_숨기고_HTTP_스레드의_traceId를_전달한다() {
        MDC.put(TraceIdFilter.MDC_KEY, "trace-stream");
        when(helpDesk.streamEvents("오류 질문", "2021001", "error"))
                .thenReturn(Flux.error(new IllegalStateException("학번 2021001 내부 모델 오류")));

        Flux<ServerSentEvent<Object>> result = controller.stream(
                new ChatRequest("오류 질문", "error"), principal("2021001"));
        MDC.clear();

        List<ServerSentEvent<Object>> events = result.collectList().block(Duration.ofSeconds(1));

        assertThat(events).extracting(ServerSentEvent::event).containsExactly("error", "done");
        assertThat(events.getFirst().data()).isEqualTo(new StreamError(
                "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.", "trace-stream"));
        assertThat(events.getFirst().data().toString())
                .doesNotContain("2021001", "내부 모델 오류", "IllegalStateException");
        assertThat(events.getLast().data()).isEqualTo(new StreamDone(false));
    }

    private static Mono<List<Object>> eventData(Flux<ServerSentEvent<Object>> events) {
        return events.map(ServerSentEvent::data).collectList();
    }

    private static Principal principal(String name) {
        return () -> name;
    }
}
