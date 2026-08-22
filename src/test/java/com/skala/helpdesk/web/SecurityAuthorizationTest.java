package com.skala.helpdesk.web;

import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.skala.helpdesk.chat.AnswerDto;
import com.skala.helpdesk.chat.HelpDeskService;
import com.skala.helpdesk.config.HelpDeskProperties;
import com.skala.helpdesk.repository.WithdrawalRequestRepository;

@WebMvcTest(controllers = {ChatController.class, AdminController.class})
@Import(SecurityConfig.class)
@ImportAutoConfiguration({
        SecurityAutoConfiguration.class,
        ServletWebSecurityAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class
})
class SecurityAuthorizationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HelpDeskService helpDesk;

    @MockitoBean
    private VectorStore vectorStore;

    @MockitoBean
    private WithdrawalRequestRepository requests;

    @MockitoBean
    private HelpDeskProperties props;

    @Test
    void 인증하지_않은_사용자는_채팅_API를_호출할_수_없다() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validChatRequest()))
                .andExpect(status().isUnauthorized())
                // 보안 필터에서 거절된 요청도 서버가 생성한 traceId로 추적할 수 있어야 한다.
                .andExpect(header().exists(TraceIdFilter.RESPONSE_HEADER));

        verifyNoInteractions(helpDesk);
    }

    @Test
    void 학생은_쿼리의_위조_학번이_아니라_인증된_학번으로_상담한다() throws Exception {
        when(helpDesk.ask("내 학점 알려줘", "2021001", "s1"))
                .thenReturn(new AnswerDto("100학점입니다.", List.of(), true));

        mockMvc.perform(post("/api/chat")
                        .with(httpBasic("2021001", "student"))
                        .param("studentId", "2021002")
                        .header(TraceIdFilter.RESPONSE_HEADER, "forged-trace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validChatRequest()))
                .andExpect(status().isOk())
                .andExpect(header().string(TraceIdFilter.RESPONSE_HEADER, not("forged-trace")));

        verify(helpDesk).ask("내 학점 알려줘", "2021001", "s1");
    }

    @Test
    void 일반_학생은_관리자_API에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/api/admin/withdrawal-requests/pending")
                        .with(httpBasic("2021001", "student")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(requests);
    }

    @Test
    void 일반_학생은_운영_지표에_접근할_수_없다() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .with(httpBasic("2021001", "student")))
                .andExpect(status().isForbidden());
    }

    @Test
    void 학생이_관리자라고_주장해도_승인_API는_403이다() throws Exception {
        mockMvc.perform(post("/api/admin/withdrawal-requests/WD-0001/approve")
                        .with(httpBasic("2021001", "student")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(requests);
    }

    @Test
    void 관리자는_관리자_API를_호출할_수_있다() throws Exception {
        when(requests.pending()).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/withdrawal-requests/pending")
                        .with(httpBasic("admin", "admin")))
                .andExpect(status().isOk());

        verify(requests).pending();
    }

    @Test
    void 관리자는_학생_역할이_없으면_채팅_API를_호출할_수_없다() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .with(httpBasic("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validChatRequest()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(helpDesk);
    }

    private String validChatRequest() {
        return """
                {"question":"내 학점 알려줘","sessionId":"s1"}
                """;
    }
}
