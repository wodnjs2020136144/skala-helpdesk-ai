package com.skala.helpdesk.tools;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
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
 * {@link AtomicInteger} 카운터를 담아 넘기면, 실제로 실행될 도구 개수만큼 이 카운터를 올려
 * 상한과 비교한다 — 싱글턴 빈에 상태를 두지 않으므로 동시 요청끼리 섞이지 않는다.
 *
 * <p><b>세는 단위는 메서드 진입 횟수가 아니라 도구 호출 개수다</b> — 처음에는
 * {@link #executeToolCalls} 진입마다 1씩 올렸는데, PR #10 교차 리뷰에서 그러면 상한을
 * 우회할 수 있다는 지적을 받았다. {@code DefaultToolCallingManager}는 한 번의 호출 안에서
 * {@code assistantMessage.getToolCalls()}를 <b>전부</b> 순회해 실행하므로(Spring AI 2.0
 * 소스 확인), 모델이 한 응답에 도구 호출 6개를 담으면 {@code max-calls=5}여도 카운터는
 * 1이고 6개가 그대로 실행된다. 그래서 배치 크기를 세고, 이번 배치를 더해 상한을 넘으면
 * delegate를 아예 호출하지 않는다.
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
            int batchSize = toolCallBatchSize(chatResponse);
            int calls = callCount.addAndGet(batchSize);
            if (calls > maxCalls) {
                throw new ToolCallLimitExceededException(
                        "도구 호출 상한(%d회)을 초과했습니다 calls=%d batch=%d".formatted(maxCalls, calls, batchSize));
            }
        }
        return delegate.executeToolCalls(prompt, chatResponse);
    }

    /**
     * 이번 실행에서 실제로 돌아갈 도구 개수. {@code DefaultToolCallingManager}가 고르는 것과
     * 같은 규칙으로 센다 — 도구 호출이 담긴 <b>첫</b> generation 하나만 실행 대상이다.
     * 도구 호출이 없으면 0을 반환하고 그대로 위임한다(delegate가 자신의 예외로 처리한다).
     */
    private static int toolCallBatchSize(ChatResponse chatResponse) {
        return chatResponse.getResults().stream()
                .map(Generation::getOutput)
                .filter(output -> output != null && output.hasToolCalls())
                .findFirst()
                .map(output -> output.getToolCalls().size())
                .orElse(0);
    }

    private static Map<String, Object> toolContextOf(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolContext() != null) {
            return options.getToolContext();
        }
        return Map.of();
    }
}
