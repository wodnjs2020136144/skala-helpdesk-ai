package com.skala.helpdesk.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import com.skala.helpdesk.web.TraceIdFilter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class AuditAdvisorTest {

    private final AuditAdvisor advisor = new AuditAdvisor();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("AI_TOOL_AUDIT");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put(TraceIdFilter.MDC_KEY, "trace-test");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void 성공_로그에는_추적ID와_마스킹_학번만_남는다() {
        ChatClientRequest request = request("주민번호와 성적 원문", "skala:2021001:s1");

        advisor.adviseCall(request, new SuccessChain());

        assertThat(messages()).singleElement().satisfies(message -> {
            assertThat(message)
                    .contains("chat call ok", "traceId=trace-test", "user=202****", "responseChars=2")
                    .doesNotContain("2021001", "주민번호", "성적 원문");
        });
    }

    @Test
    void 실패_로그에는_예외_메시지_대신_예외_유형만_남는다() {
        ChatClientRequest request = request("질문 원문", "skala:2021001:s1");
        CallAdvisorChain chain = new FailingChain();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> advisor.adviseCall(request, chain))
                .isInstanceOf(IllegalStateException.class);

        assertThat(messages()).singleElement().satisfies(message -> {
            assertThat(message)
                    .contains("chat call failed", "traceId=trace-test", "user=202****",
                            "errorType=IllegalStateException")
                    .doesNotContain("2021001", "4.5", "비밀 오류", "질문 원문");
        });
    }

    private ChatClientRequest request(String question, String conversationId) {
        return new ChatClientRequest(new Prompt(question),
                Map.of(ChatMemory.CONVERSATION_ID, conversationId));
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static final class SuccessChain implements CallAdvisorChain {
        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            return ChatClientResponse.builder()
                    .chatResponse(new ChatResponse(List.of(
                            new Generation(new AssistantMessage("정상")))))
                    .context(request.context())
                    .build();
        }

        @Override
        public List<CallAdvisor> getCallAdvisors() {
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(CallAdvisor after) {
            return this;
        }
    }

    private static final class FailingChain implements CallAdvisorChain {
        @Override
        public ChatClientResponse nextCall(ChatClientRequest request) {
            throw new IllegalStateException("2021001 학생 GPA 4.5 비밀 오류");
        }

        @Override
        public List<CallAdvisor> getCallAdvisors() {
            return List.of();
        }

        @Override
        public CallAdvisorChain copy(CallAdvisor after) {
            return this;
        }
    }
}
