package com.skala.helpdesk.advisor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import com.skala.helpdesk.repository.StudentRecordRepository;
import com.skala.helpdesk.tools.AcademicTools;
import com.skala.helpdesk.web.TraceIdFilter;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@SpringJUnitConfig(ToolAuditIntegrationTest.Config.class)
class ToolAuditIntegrationTest {

    @Autowired
    private AcademicTools academicTools;

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger("AI_TOOL_AUDIT");
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        MDC.put(TraceIdFilter.MDC_KEY, "trace-callback");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void Spring_AI_Tool_Callback으로_호출해도_감사_Aspect가_실행된다() {
        var provider = MethodToolCallbackProvider.builder().toolObjects(academicTools).build();
        var callback = Arrays.stream(provider.getToolCallbacks())
                .filter(tool -> tool.getToolDefinition().name().equals("myCourses"))
                .findFirst()
                .orElseThrow();

        String result = callback.call("{}", new ToolContext(Map.of("studentId", "2021001")));

        assertThat(result).contains("누적 이수 학점: 100학점");
        assertThat(appender.list).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage())
                        .contains("tool call ok", "traceId=trace-callback", "tool=myCourses", "user=202****")
                        .doesNotContain("2021001", "100학점"));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    @Import({ToolAuditAspect.class, StudentRecordRepository.class, AcademicTools.class})
    static class Config {
    }
}
