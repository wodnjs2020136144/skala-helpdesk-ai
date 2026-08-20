package com.skala.helpdesk.web;

import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.skala.helpdesk.chat.AnswerDto;
import com.skala.helpdesk.chat.HelpDeskService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 담당: B(첫 번째 책임자) · 리뷰: A — Phase 3(p.316) 동기 API·Phase 6(p.320) SSE.
 *
 * <p>사용자(학번) 식별은 실습 편의를 위해 요청 파라미터로 받는다 — Phase 7에서
 * {@code SecurityConfig}가 완성되면 인증 주체({@code Principal})에서 꺼내도록 바꾼다.
 *
 * <p>참고: {@code day3-consult-agent/web/ConsultController.java}, 교안 Phase 6 코드(p.320)
 *
 * <p>완료 기준: 동기 API가 {@link AnswerDto}(답변+출처+toolUsed)를 반환한다. SSE는 토큰을
 * 스트리밍하고 마지막에 출처 이벤트를 보낸다.
 */
@RestController
@Tag(name = "HelpDesk · 상담")
public class ChatController {

    private final HelpDeskService helpDesk;

    public ChatController(HelpDeskService helpDesk) {
        this.helpDesk = helpDesk;
    }

    @PostMapping("/api/chat")
    @Operation(summary = "동기 상담", description = "RAG 규정 답변 + 학사 조회/철회 도구 + 대화 메모리")
    public AnswerDto chat(@RequestBody ChatRequest request,
                          @RequestParam(defaultValue = "2021001") String studentId) {
        return helpDesk.ask(request.question(), studentId, request.sessionId());
    }

    // TODO(B, Phase 6): 스트림 완료 후 마지막 sources 이벤트를 붙인다(교안 p.320 코드
    // 참고 — 지금은 토큰 이벤트만 보낸다. HelpDeskService에 lastSources 같은 헬퍼를
    // 추가하거나, 동기 ask()를 먼저 호출해 출처를 얻는 방식 중 A와 상의해 정한다).
    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "SSE 스트리밍 상담")
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequest request,
                                                @RequestParam(defaultValue = "2021001") String studentId) {
        return helpDesk.stream(request.question(), studentId, request.sessionId())
                .map(token -> ServerSentEvent.builder(token).event("token").build())
                .concatWith(Mono.just(ServerSentEvent.builder("[]").event("sources").build()))
                .timeout(Duration.ofSeconds(60));
    }

    @GetMapping("/api/chat/history")
    @Operation(summary = "대화 이력 조회")
    public java.util.List<String> history(@RequestParam String sessionId,
                                          @RequestParam(defaultValue = "2021001") String studentId) {
        return helpDesk.history(studentId, sessionId);
    }

    @DeleteMapping("/api/chat/history")
    @Operation(summary = "대화 이력 초기화", description = "Advisor 순서 실험 후 원복할 때 사용한다.")
    public void clearHistory(@RequestParam String sessionId,
                             @RequestParam(defaultValue = "2021001") String studentId) {
        helpDesk.clearHistory(studentId, sessionId);
    }

    public record ChatRequest(String question, String sessionId) {
    }
}
