package com.skala.helpdesk.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

/**
 * 담당: A. Phase 6 — {@code helpdesk.tool.max-calls}를 실제로 강제하는
 * {@link BoundedToolCallingManager}가 상한 이내에서는 위임하고, 상한을 넘으면 delegate를
 * 부르지 않은 채 차단하며, 요청 간 카운터가 섞이지 않는지 확인한다.
 *
 * <p>PR #10 교차 리뷰(성우) 지적 반영 — {@code DefaultToolCallingManager}는 한 번의
 * {@code executeToolCalls()} 호출 안에서 해당 응답에 담긴 도구 호출을 <b>전부</b> 실행한다.
 * 그래서 여기 테스트 응답에도 실제 {@link AssistantMessage.ToolCall}을 담아, 배치 크기가
 * 곧 카운터 증가량이 되는지 검증한다 — 메서드 진입 횟수만 세면 한 응답에 여러 도구 호출이
 * 있을 때 상한을 우회할 수 있다.
 */
class BoundedToolCallingManagerTest {

    @Test
    void 상한_이내면_delegate에_위임하고_배치_크기만큼_카운터가_올라간다() {
        AtomicInteger counter = new AtomicInteger(0);
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult expected = ToolExecutionResult.builder().conversationHistory(List.of()).build();
        when(delegate.executeToolCalls(any(), any())).thenReturn(expected);
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 3);

        ToolExecutionResult result = manager.executeToolCalls(promptWithCounter(counter), toolCallResponse(1));

        assertThat(result).isSameAs(expected);
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void 누적_호출이_상한을_넘으면_delegate를_부르지_않고_예외를_던진다() {
        AtomicInteger counter = new AtomicInteger(2); // 이미 2회 호출된 상태 — 다음 호출이 3번째
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 2);

        assertThatThrownBy(() -> manager.executeToolCalls(promptWithCounter(counter), toolCallResponse(1)))
                .isInstanceOf(ToolCallLimitExceededException.class);

        verifyNoInteractions(delegate);
        assertThat(counter.get()).isEqualTo(3);
    }

    @Test
    void 한_응답에_담긴_도구_호출_배치가_잔여_한도를_넘으면_delegate를_부르지_않고_예외를_던진다() {
        // 카운터는 아직 0(첫 호출)이지만, 모델이 한 응답에 도구 호출을 6개 담아 max-calls(5)를
        // 단번에 넘는 경우 — 메서드 진입 횟수만 세면 이 배치를 통째로 통과시켜버린다.
        AtomicInteger counter = new AtomicInteger(0);
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 5);

        assertThatThrownBy(() -> manager.executeToolCalls(promptWithCounter(counter), toolCallResponse(6)))
                .isInstanceOf(ToolCallLimitExceededException.class);

        verifyNoInteractions(delegate);
        assertThat(counter.get()).isEqualTo(6);
    }

    @Test
    void 카운터가_없으면_상한을_적용하지_않고_그대로_위임한다() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult expected = ToolExecutionResult.builder().conversationHistory(List.of()).build();
        when(delegate.executeToolCalls(any(), any())).thenReturn(expected);
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 1);

        Prompt promptWithoutCounter = new Prompt(new UserMessage("질문"), ChatOptions.builder().build());
        ChatResponse response = toolCallResponse(5); // 상한보다 큰 배치를 줘도 카운터가 없으면 검사하지 않는다.
        ToolExecutionResult result = manager.executeToolCalls(promptWithoutCounter, response);

        assertThat(result).isSameAs(expected);
        verify(delegate, times(1)).executeToolCalls(promptWithoutCounter, response);
    }

    @Test
    void 서로_다른_두_요청의_카운터는_섞이지_않는다() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        when(delegate.executeToolCalls(any(), any()))
                .thenReturn(ToolExecutionResult.builder().conversationHistory(List.of()).build());
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 5);

        AtomicInteger counterA = new AtomicInteger(0);
        AtomicInteger counterB = new AtomicInteger(0);
        manager.executeToolCalls(promptWithCounter(counterA), toolCallResponse(1));
        manager.executeToolCalls(promptWithCounter(counterA), toolCallResponse(1));
        manager.executeToolCalls(promptWithCounter(counterB), toolCallResponse(1));

        assertThat(counterA.get()).isEqualTo(2);
        assertThat(counterB.get()).isEqualTo(1);
    }

    // --- 테스트 헬퍼 ---

    private static Prompt promptWithCounter(AtomicInteger counter) {
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .toolContext(Map.of(BoundedToolCallingManager.TOOL_CALL_COUNTER, counter))
                .build();
        return new Prompt(new UserMessage("질문"), options);
    }

    /**
     * {@code DefaultToolCallingManager}가 실제로 실행하는 것과 같은 모양 — 도구 호출이
     * {@code toolCallCount}개 담긴 {@link AssistantMessage} 응답. delegate mock이 실행
     * 자체는 대신하므로 이름·인자는 의미 없이 채운다.
     */
    private static ChatResponse toolCallResponse(int toolCallCount) {
        List<AssistantMessage.ToolCall> toolCalls = IntStream.range(0, toolCallCount)
                .mapToObj(i -> new AssistantMessage.ToolCall("call-" + i, "function", "aTool", "{}"))
                .toList();
        AssistantMessage message = AssistantMessage.builder().toolCalls(toolCalls).build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
