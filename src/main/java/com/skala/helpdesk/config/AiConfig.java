package com.skala.helpdesk.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import com.skala.helpdesk.advisor.AuditAdvisor;
import com.skala.helpdesk.advisor.SafeGuardAdvisor;
import com.skala.helpdesk.advisor.TokenMeterAdvisor;
import com.skala.helpdesk.tools.AcademicTools;
import com.skala.helpdesk.tools.RequestTools;

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
                                        .build())
                                .order(300)
                                .build())
                .defaultTools(academicTools, requestTools)
                .build();
    }

    private String systemPrompt() throws IOException {
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:prompts/system.st");
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }
}
