package com.skala.helpdesk.advisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;

import com.skala.helpdesk.web.TraceIdFilter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ToolAuditAspectTest {

    private final ToolAuditAspect aspect = new ToolAuditAspect();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("AI_TOOL_AUDIT");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put(TraceIdFilter.MDC_KEY, "trace-tool");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void 도구_성공은_인자와_결과를_제외하고_감사한다() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("gradStatus", "누적 학점 100, GPA 4.5", false);

        Object result = aspect.auditTool(joinPoint);

        assertThat(result).isEqualTo("누적 학점 100, GPA 4.5");
        assertThat(messages()).singleElement().satisfies(message -> {
            assertThat(message)
                    .contains("tool call ok", "traceId=trace-tool", "tool=gradStatus", "user=202****")
                    .doesNotContain("2021001", "GPA", "4.5", "누적 학점");
        });
    }

    @Test
    void 도구_실패는_예외_메시지_없이_유형만_감사한다() throws Throwable {
        ProceedingJoinPoint joinPoint = joinPoint("requestDrop", null, true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> aspect.auditTool(joinPoint))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(messages()).singleElement().satisfies(message -> {
            assertThat(message)
                    .contains("tool call failed", "traceId=trace-tool", "tool=requestDrop",
                            "user=202****", "errorType=IllegalArgumentException")
                    .doesNotContain("2021001", "개인 사유", "비밀 실패");
        });
    }

    private ProceedingJoinPoint joinPoint(String methodName, Object result, boolean fail) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        Signature signature = mock(Signature.class);
        when(signature.getName()).thenReturn(methodName);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(new Object[]{
                "개인 사유",
                new ToolContext(Map.of("studentId", "2021001"))
        });
        if (fail) {
            when(joinPoint.proceed()).thenThrow(
                    new IllegalArgumentException("2021001 개인 사유 비밀 실패"));
        }
        else {
            when(joinPoint.proceed()).thenReturn(result);
        }
        return joinPoint;
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
