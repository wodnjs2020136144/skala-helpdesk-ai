package com.skala.helpdesk.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.skala.helpdesk.advisor.AuditAdvisor;
import com.skala.helpdesk.advisor.SafeGuardAdvisor;
import com.skala.helpdesk.advisor.TokenMeterAdvisor;
import com.skala.helpdesk.rag.IngestService;
import com.skala.helpdesk.tools.AcademicTools;
import com.skala.helpdesk.tools.BoundedToolCallingManager;
import com.skala.helpdesk.tools.RequestTools;

import io.micrometer.observation.ObservationRegistry;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 1 (교안 p.313).
 *
 * <p>공급자·모델·임계값은 전부 application.yml({@link HelpDeskProperties})로 뺀다.
 * 코드에 상수를 남기지 않는다. <b>Advisor 순서가 곧 정책이다</b> — 확정 순서표
 * (docs/분업-역할표.md "Advisor 순서"): Audit(0) → TokenMeter(10) → SafeGuard(100) →
 * Memory(200) → RAG(300). A가 이 순서로 조립하고, B가 보안 관점에서 검토한다
 * (공동 책임 — 어느 한쪽도 혼자 결정하지 않는다).
 *
 * <p>참고: {@code day3-consult-agent/config/Day3AiConfig.java},
 * {@code SpringAI_실습/ch11_advisors/MemoryChatConfig.java}
 *
 * <p>완료 기준: 규정 답변에 출처가 붙는다({@link QuestionAnswerAdvisor}) · 차단(order 100)이
 * 메모리 저장(order 200)보다 앞이다.
 */
@Configuration
public class AiConfig {

    @Bean
    public ChatClient helpDeskClient(ChatClient.Builder builder,
                                     VectorStore vectorStore,
                                     ChatMemory chatMemory,
                                     HelpDeskProperties props,
                                     AuditAdvisor audit,
                                     TokenMeterAdvisor tokenMeter,
                                     SafeGuardAdvisor safeGuard,
                                     AcademicTools academicTools,
                                     RequestTools requestTools) throws IOException {
        return builder
                .defaultSystem(systemPrompt())
                .defaultAdvisors(
                        audit,
                        tokenMeter,
                        safeGuard,
                        MessageChatMemoryAdvisor.builder(chatMemory).order(200).build(),
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder()
                                        .topK(props.rag().topK())
                                        .similarityThreshold(props.rag().threshold())
                                        // 운영 검색은 현행 본칙만 본다 — 부칙(개정 이력의
                                        // 시행일·경과조치)이 섞이면 폐지된 옛 기준이 현행
                                        // 규정처럼 답변에 들어간다(IngestService.splitBySection
                                        // Javadoc의 26학점 실측 사례). 관리자 진단
                                        // (AdminController)은 필터 없이 조회하므로 부칙도 보인다.
                                        .filterExpression("%s == '%s'"
                                                .formatted(IngestService.SECTION, IngestService.MAIN))
                                        .build())
                                .order(300)
                                .build())
                .defaultTools(academicTools, requestTools)
                .build();
    }

    /**
     * {@code helpdesk.tool.max-calls}를 실제로 강제하는 연결 지점(PR #6 리뷰 질문 3).
     * Spring AI 2.0의 {@code ToolCallingAutoConfiguration}이 등록하는 기본 빈과 똑같은
     * 협력자({@code ToolCallbackResolver}·{@code ToolExecutionExceptionProcessor}·
     * {@code ObservationRegistry})로 위임 대상을 만들고 {@link BoundedToolCallingManager}로
     * 감싼다 — {@code @ConditionalOnMissingBean}이라 이 빈이 있으면 기본 빈은 등록되지 않고,
     * {@code OpenAiChatModel} 빈이 이 빈을 그대로 받아 쓴다.
     */
    @Bean
    public ToolCallingManager toolCallingManager(ToolCallbackResolver toolCallbackResolver,
                                                 ToolExecutionExceptionProcessor toolExecutionExceptionProcessor,
                                                 ObjectProvider<ObservationRegistry> observationRegistry,
                                                 HelpDeskProperties props) {
        ToolCallingManager delegate = ToolCallingManager.builder()
                .observationRegistry(observationRegistry.getIfUnique(() -> ObservationRegistry.NOOP))
                .toolCallbackResolver(toolCallbackResolver)
                .toolExecutionExceptionProcessor(toolExecutionExceptionProcessor)
                .build();
        return new BoundedToolCallingManager(delegate, props.tool().maxCalls());
    }

    private String systemPrompt() throws IOException {
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:prompts/system.st");
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
