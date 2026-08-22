package com.skala.helpdesk.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

class HelpDeskServiceMemoryTest {

    private MessageWindowChatMemory memory;
    private HelpDeskService service;

    @BeforeEach
    void setUp() {
        memory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
        service = new HelpDeskService(mock(ChatClient.class), memory);
    }

    @Test
    void 대화_ID는_학번과_세션을_모두_포함한다() {
        assertThat(service.conversationId("2021001", "s1"))
                .isEqualTo("skala:2021001:s1");
    }

    @Test
    void 같은_학번의_다른_세션은_대화가_섞이지_않는다() {
        add("2021001", "graduation", "졸업 학점은 130학점입니다.");
        add("2021001", "scholarship", "장학금 기준을 알려주세요.");

        assertThat(service.history("2021001", "graduation"))
                .singleElement()
                .asString()
                .contains("졸업 학점은 130학점입니다.");
        assertThat(service.history("2021001", "graduation"))
                .noneMatch(message -> message.contains("장학금"));
    }

    @Test
    void 다른_학번의_같은_세션명은_대화가_섞이지_않는다() {
        add("2021001", "s1", "박성우 학생의 대화");
        add("2021002", "s1", "다른 학생의 대화");

        assertThat(service.history("2021001", "s1"))
                .singleElement()
                .asString()
                .contains("박성우 학생의 대화");
        assertThat(service.history("2021001", "s1"))
                .noneMatch(message -> message.contains("다른 학생"));
    }

    @Test
    void 메모리는_최근_20개_메시지만_유지한다() {
        for (int index = 1; index <= 21; index++) {
            add("2021001", "window", "메시지-%02d".formatted(index));
        }

        assertThat(service.history("2021001", "window"))
                .hasSize(20)
                .noneMatch(message -> message.contains("메시지-01"))
                .anyMatch(message -> message.contains("메시지-02"))
                .anyMatch(message -> message.contains("메시지-21"));
    }

    @Test
    void 이력_삭제는_지정한_학번과_세션에만_적용된다() {
        add("2021001", "clear", "삭제할 대화");
        add("2021002", "clear", "유지할 대화");

        service.clearHistory("2021001", "clear");

        assertThat(service.history("2021001", "clear")).isEmpty();
        assertThat(service.history("2021002", "clear"))
                .singleElement()
                .asString()
                .contains("유지할 대화");
    }

    private void add(String studentId, String sessionId, String text) {
        memory.add(service.conversationId(studentId, sessionId), new UserMessage(text));
    }
}
