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
 * <p>{@code conversationId} 규칙(테넌트·학번·세션)은 여기가 아니라 {@code HelpDeskService}
 * 에서 한 곳에 모은다 — 흩어지면 남의 대화가 섞인다.
 *
 * <p>참고: {@code day3-consult-agent/config/Day3ChatMemoryConfig.java},
 * {@code SpringAI_실습/spring-ai-2-step-samples/step05-memory}
 *
 * <p><b>검증 완료(2026-08-22, 실제 OpenAI + pgvector)</b> — 상세 결과는
 * {@code docs/검증-시나리오.md} ②③⑥ 행 참고.
 * <ul>
 *   <li>스키마 — {@code \dt}로 {@code spring_ai_chat_memory} 테이블 존재 확인(schema SQL
 *       정상 적용)</li>
 *   <li>재기동 유지 — 앱을 종료·재기동한 뒤에도 이전 대화(3턴, 6개 메시지)가 그대로 조회됨</li>
 *   <li>학번·세션 격리 — DB에서 {@code conversation_id}별로 메시지가 완전히 분리 저장됨을
 *       직접 확인(같은 세션 문자열이어도 학번이 다르면 다른 conversation_id)</li>
 *   <li>20개 윈도우 — 12턴(24개 메시지) 대화 후 조회 결과가 최근 20개로 잘림을 확인</li>
 * </ul>
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
