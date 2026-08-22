package com.skala.helpdesk.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
 */
class BoundedToolCallingManagerTest {

    @Test
    void 상한_이내면_delegate에_위임하고_카운터가_올라간다() {
        AtomicInteger counter = new AtomicInteger(0);
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult expected = ToolExecutionResult.builder().conversationHistory(List.of()).build();
        when(delegate.executeToolCalls(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(expected);
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 3);

        ToolExecutionResult result = manager.executeToolCalls(promptWithCounter(counter), toolCallResponse());

        assertThat(result).isSameAs(expected);
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    void 상한을_넘으면_delegate를_부르지_않고_예외를_던진다() {
        AtomicInteger counter = new AtomicInteger(2); // 이미 2회 호출된 상태 — 다음 호출이 3번째
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 2);

        assertThatThrownBy(() -> manager.executeToolCalls(promptWithCounter(counter), toolCallResponse()))
                .isInstanceOf(ToolCallLimitExceededException.class);

        verifyNoInteractions(delegate);
    }

    @Test
    void 카운터가_없으면_상한을_적용하지_않고_그대로_위임한다() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        ToolExecutionResult expected = ToolExecutionResult.builder().conversationHistory(List.of()).build();
        when(delegate.executeToolCalls(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(expected);
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 1);

        Prompt promptWithoutCounter = new Prompt(new UserMessage("질문"), ChatOptions.builder().build());
        ChatResponse response = toolCallResponse();
        ToolExecutionResult result = manager.executeToolCalls(promptWithoutCounter, response);

        assertThat(result).isSameAs(expected);
        verify(delegate, times(1)).executeToolCalls(promptWithoutCounter, response);
    }

    @Test
    void 서로_다른_두_요청의_카운터는_섞이지_않는다() {
        ToolCallingManager delegate = mock(ToolCallingManager.class);
        when(delegate.executeToolCalls(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(ToolExecutionResult.builder().conversationHistory(List.of()).build());
        BoundedToolCallingManager manager = new BoundedToolCallingManager(delegate, 5);

        AtomicInteger counterA = new AtomicInteger(0);
        AtomicInteger counterB = new AtomicInteger(0);
        manager.executeToolCalls(promptWithCounter(counterA), toolCallResponse());
        manager.executeToolCalls(promptWithCounter(counterA), toolCallResponse());
        manager.executeToolCalls(promptWithCounter(counterB), toolCallResponse());

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

    /** 실제 도구 호출 실행에 필요한 내용은 delegate mock이 대신하므로 최소한의 응답만 있으면 된다. */
    private static ChatResponse toolCallResponse() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage("답변"))));
    }
}
