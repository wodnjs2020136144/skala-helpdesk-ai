package com.skala.helpdesk.web;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.skala.helpdesk.chat.AnswerDto;
import com.skala.helpdesk.chat.AnswerDto.Source;
import com.skala.helpdesk.chat.HelpDeskService;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private HelpDeskService helpDesk;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(new ChatController(helpDesk))
                .setControllerAdvice(new ChatValidationExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void 동기_상담은_답변_출처와_도구_사용_여부를_반환한다() throws Exception {
        when(helpDesk.ask("제가 지금 몇 학점이죠?", "2021001", "s1"))
                .thenReturn(new AnswerDto(
                        "현재 누적 이수 학점은 100학점입니다.",
                        List.of(new Source("graduation-requirements.md", "2026-08")),
                        true));

        mockMvc.perform(post("/api/chat")
                        .param("studentId", "2021001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"제가 지금 몇 학점이죠?","sessionId":"s1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("현재 누적 이수 학점은 100학점입니다."))
                .andExpect(jsonPath("$.sources[0].document").value("graduation-requirements.md"))
                .andExpect(jsonPath("$.sources[0].version").value("2026-08"))
                .andExpect(jsonPath("$.toolUsed").value(true));

        verify(helpDesk).ask("제가 지금 몇 학점이죠?", "2021001", "s1");
    }

    @Test
    void 근거가_없는_답변은_빈_출처_목록을_유지한다() throws Exception {
        when(helpDesk.ask("학생식당 메뉴 알려줘", "2021001", "no-source"))
                .thenReturn(new AnswerDto("정확한 규정을 확인할 수 없습니다.", List.of(), false));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"학생식당 메뉴 알려줘","sessionId":"no-source"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("정확한 규정을 확인할 수 없습니다."))
                .andExpect(jsonPath("$.sources").isEmpty())
                .andExpect(jsonPath("$.toolUsed").value(false));

        verify(helpDesk).ask("학생식당 메뉴 알려줘", "2021001", "no-source");
    }

    @Test
    void 질문이_비어_있으면_400을_반환하고_서비스를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"   ","sessionId":"s1"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 값을 확인해 주세요."))
                .andExpect(jsonPath("$.fieldErrors.question").value("질문을 입력해 주세요."));

        verifyNoInteractions(helpDesk);
    }

    @Test
    void 세션_ID가_비어_있으면_400을_반환하고_서비스를_호출하지_않는다() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"졸업 요건 알려줘","sessionId":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sessionId").value("세션 ID를 입력해 주세요."));

        verifyNoInteractions(helpDesk);
    }

    @Test
    void 세션_ID가_100자를_넘으면_400을_반환한다() throws Exception {
        String sessionId = "s".repeat(101);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question":"졸업 요건 알려줘","sessionId":"%s"}
                                """.formatted(sessionId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.sessionId")
                        .value("세션 ID는 100자 이하여야 합니다."));

        verifyNoInteractions(helpDesk);
    }

    @Test
    void 스트리밍_상담도_같은_요청_검증을_적용한다() throws Exception {
        mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("""
                                {"question":"","sessionId":"s1"}
                                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(helpDesk);
    }
}
