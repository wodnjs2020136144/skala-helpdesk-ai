package com.skala.helpdesk.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 5 (교안 p.319).
 *
 * <p>day3-consult-agent(SimpleVectorStore·인메모리 대화)와 달리, 이 종합 실습은
 * 재시작해도 대화가 유지돼야 한다 — {@code spring-ai-starter-model-chat-memory-repository-jdbc}
 * 의존성이 {@link ChatMemoryRepository} 빈을 JDBC 구현으로 자동 등록한다(수동 빈 등록 불필요,
 * day3의 {@code InMemoryChatMemoryRepository} 수동 등록과 차이가 이것이다).
 *
 * <p>TODO(A, Phase 5): 아래를 확인한다.
 * <ul>
 *   <li>스키마 초기화 — Spring AI JDBC 스타터가 제공하는 schema SQL이 pgvector 컨테이너에
 *       실제로 적용되는지(콘솔 로그 또는 {@code \\dt}로 테이블 존재 확인)</li>
 *   <li>{@code conversationId} 규칙(테넌트·학번·세션)은 여기가 아니라
 *       {@code HelpDeskService}에서 한 곳에 모은다 — 흩어지면 남의 대화가 섞인다</li>
 * </ul>
 *
 * <p>참고: {@code day3-consult-agent/config/Day3ChatMemoryConfig.java},
 * {@code SpringAI_실습/spring-ai-2-step-samples/step05-memory}
 *
 * <p>완료 기준: 앱을 재기동해도 이전 대화 맥락이 남아 있다. 서로 다른 학번·세션의
 * 대화가 섞이지 않는다.
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository, HelpDeskProperties props) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(props.memory().maxMessages())
                .build();
    }
}
