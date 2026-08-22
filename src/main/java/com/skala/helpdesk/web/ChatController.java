package com.skala.helpdesk.web;

import java.security.Principal;
import java.util.UUID;

import org.slf4j.MDC;
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
import com.skala.helpdesk.chat.StreamEvent;
import com.skala.helpdesk.chat.StreamEvent.Done;
import com.skala.helpdesk.chat.StreamEvent.Sources;
import com.skala.helpdesk.chat.StreamEvent.Token;
import com.skala.helpdesk.config.ChatProperties;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import reactor.core.publisher.Flux;

/**
 * 담당: B(첫 번째 책임자) · 리뷰: A — Phase 3(p.316) 동기 API·Phase 6(p.320) SSE.
 *
 * <p>사용자(학번)는 요청 파라미터가 아니라 인증 주체({@link Principal})에서만 꺼낸다.
 * 클라이언트가 {@code studentId} 쿼리를 임의로 붙여도 서비스와 ToolContext에는 인증된
 * 사용자 이름만 전달된다.
 *
 * <p>참고: {@code day3-consult-agent/web/ConsultController.java}, 교안 Phase 6 코드(p.320)
 *
 * <p>완료 기준: 동기 API가 {@link AnswerDto}(답변+출처+toolUsed)를 반환한다. SSE는 토큰을
 * 스트리밍하고 마지막에 출처 이벤트를 보낸다.
 */
@RestController
@Tag(name = "HelpDesk · 상담")
public class ChatController {

    private static final String SAFE_ERROR_MESSAGE =
            "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

    private final HelpDeskService helpDesk;
    private final ChatProperties chatProperties;

    public ChatController(HelpDeskService helpDesk, ChatProperties chatProperties) {
        this.helpDesk = helpDesk;
        this.chatProperties = chatProperties;
    }

    @PostMapping("/api/chat")
    @Operation(summary = "동기 상담", description = "RAG 규정 답변 + 학사 조회/철회 도구 + 대화 메모리")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "상담 성공",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AnswerDto.class),
                            examples = {
                                    @ExampleObject(name = "규정 근거 답변", value = """
                                            {"answer":"졸업하려면 총 130학점 이상이 필요합니다.",
                                             "sources":[{"document":"graduation-requirements.md","version":"2026-08"}],
                                             "toolUsed":false}
                                            """),
                                    @ExampleObject(name = "근거 없음", value = """
                                            {"answer":"정확한 규정을 확인할 수 없습니다.",
                                             "sources":[],"toolUsed":false}
                                            """)
                            })),
            @ApiResponse(responseCode = "400", description = "질문 또는 세션 ID 입력 오류")
    })
    public AnswerDto chat(@Valid @RequestBody ChatRequest request,
                          Principal principal) {
        return helpDesk.ask(request.question(), principal.getName(), request.sessionId());
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "SSE 스트리밍 상담",
            description = "token 이벤트 뒤 실제 sources와 done을 보내며, 실패 시 error와 done으로 종료합니다.")
    public Flux<ServerSentEvent<Object>> stream(@Valid @RequestBody ChatRequest request,
                                                Principal principal) {
        // TraceIdFilter의 MDC는 리액티브 스레드까지 전파되지 않으므로 HTTP 요청 스레드에서 캡처한다.
        String traceId = currentTraceId();
        return helpDesk.streamEvents(request.question(), principal.getName(), request.sessionId())
                .map(this::toServerSentEvent)
                .timeout(chatProperties.streamTimeout())
                .onErrorResume(error -> Flux.just(
                        event("error", new StreamError(SAFE_ERROR_MESSAGE, traceId)),
                        event("done", new StreamDone(false))));
    }

    private ServerSentEvent<Object> toServerSentEvent(StreamEvent streamEvent) {
        return switch (streamEvent) {
            case Token token -> event("token", token.text());
            case Sources sources -> event("sources", sources.sources());
            case Done done -> event("done", new StreamDone(done.toolUsed()));
        };
    }

    private ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }

    private String currentTraceId() {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        return traceId == null || traceId.isBlank() ? UUID.randomUUID().toString() : traceId;
    }

    @GetMapping("/api/chat/history")
    @Operation(summary = "대화 이력 조회")
    public java.util.List<String> history(@RequestParam String sessionId,
                                          Principal principal) {
        return helpDesk.history(principal.getName(), sessionId);
    }

    @DeleteMapping("/api/chat/history")
    @Operation(summary = "대화 이력 초기화", description = "Advisor 순서 실험 후 원복할 때 사용한다.")
    public void clearHistory(@RequestParam String sessionId,
                             Principal principal) {
        helpDesk.clearHistory(principal.getName(), sessionId);
    }

    public record ChatRequest(
            @NotBlank(message = "질문을 입력해 주세요.")
            @Schema(description = "학사 상담 질문", example = "졸업 학점 요건이 어떻게 돼요?")
            String question,
            @NotBlank(message = "세션 ID를 입력해 주세요.")
            @Size(max = 100, message = "세션 ID는 100자 이하여야 합니다.")
            @Schema(description = "대화 문맥을 구분하는 세션 ID", example = "s1")
            String sessionId) {
    }

    public record StreamDone(boolean toolUsed) {
    }

    public record StreamError(String message, String traceId) {
    }
}
