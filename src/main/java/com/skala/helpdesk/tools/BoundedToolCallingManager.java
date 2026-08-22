package com.skala.helpdesk.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 담당: A. {@code helpdesk.tool.max-calls}를 실제로 강제한다(PR #6 리뷰 질문 3 — B가 물었지만
 * A 소유 {@code AiConfig}가 연결 지점이라 가져왔다). Spring AI 2.0에는 내장 반복 상한이 없다.
 *
 * <p>같은 도구를 무한 호출하는 함정을 막는 안전장치다(교안 함정, {@code HelpDeskProperties}
 * Javadoc 참고). {@code HelpDeskService.askWith()}가 {@code toolContext}에 요청 단위
 * {@link AtomicInteger} 카운터를 담아 넘기면, 도구가 호출될 때마다 이 카운터를 올려 상한과
 * 비교한다 — 싱글턴 빈에 상태를 두지 않으므로 동시 요청끼리 섞이지 않는다.
 *
 * <p><b>카운터가 없으면 그대로 위임한다</b> — {@code HelpDeskService}를 거치지 않은 호출
 * (예: 관리자 API가 직접 {@code ChatClient}를 쓰게 될 경우)을 깨뜨리지 않기 위해서다.
 */
public class BoundedToolCallingManager implements ToolCallingManager {

    /** {@code HelpDeskService.askWith()}가 toolContext에 담는 요청 단위 카운터의 키. */
    public static final String TOOL_CALL_COUNTER = "toolCallCounter";

    private final ToolCallingManager delegate;
    private final int maxCalls;

    public BoundedToolCallingManager(ToolCallingManager delegate, int maxCalls) {
        this.delegate = delegate;
        this.maxCalls = maxCalls;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        return delegate.resolveToolDefinitions(chatOptions);
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Object counter = toolContextOf(prompt).get(TOOL_CALL_COUNTER);
        if (counter instanceof AtomicInteger callCount) {
            int calls = callCount.incrementAndGet();
            if (calls > maxCalls) {
                throw new ToolCallLimitExceededException(
                        "도구 호출 상한(%d회)을 초과했습니다 calls=%d".formatted(maxCalls, calls));
            }
        }
        return delegate.executeToolCalls(prompt, chatResponse);
    }

    private static Map<String, Object> toolContextOf(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null) {
            return options.getToolContext();
        }
        return Map.of();
    }
}
