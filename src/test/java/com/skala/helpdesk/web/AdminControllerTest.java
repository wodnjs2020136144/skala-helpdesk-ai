package com.skala.helpdesk.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.skala.helpdesk.config.HelpDeskProperties;
import com.skala.helpdesk.repository.WithdrawalRequestRepository;

@ExtendWith(MockitoExtension.class)
class AdminControllerTest {

    @Mock
    private VectorStore vectorStore;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        var props = new HelpDeskProperties(
                new HelpDeskProperties.Rag(5, 0.3, 300, 150),
                new HelpDeskProperties.Memory(20),
                new HelpDeskProperties.Tool(5));
        var controller = new AdminController(
                vectorStore, new WithdrawalRequestRepository(), props);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 청크의_메타데이터와_미리보기를_반환한다() throws Exception {
        String content = "가".repeat(170);
        Document document = Document.builder()
                .text(content)
                .metadata(Map.of(
                        "source", "graduation-requirements.md",
                        "docType", "academic",
                        "dept", "academic-affairs",
                        "version", "2026-08"))
                .score(0.82)
                .build();
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        mockMvc.perform(get("/api/admin/chunks").param("q", "  졸업 학점  ").param("topK", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].source").value("graduation-requirements.md"))
                .andExpect(jsonPath("$[0].docType").value("academic"))
                .andExpect(jsonPath("$[0].dept").value("academic-affairs"))
                .andExpect(jsonPath("$[0].version").value("2026-08"))
                .andExpect(jsonPath("$[0].score").value(0.82))
                .andExpect(jsonPath("$[0].preview").value("가".repeat(160)));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore).similaritySearch(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getQuery()).isEqualTo("졸업 학점");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getTopK()).isEqualTo(3);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getSimilarityThreshold()).isEqualTo(0.3);
    }

    @Test
    void 검색_결과가_null이면_빈_목록을_반환한다() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(null);

        mockMvc.perform(get("/api/admin/chunks").param("q", "장학금"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void 검색_결과가_없으면_빈_목록을_반환한다() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        mockMvc.perform(get("/api/admin/chunks").param("q", "없는 규정"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void 점수와_본문이_null이어도_응답한다() throws Exception {
        Document document = org.mockito.Mockito.mock(Document.class);
        when(document.getMetadata()).thenReturn(Map.of());
        when(document.getText()).thenReturn(null);
        when(document.getScore()).thenReturn(null);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(document));

        mockMvc.perform(get("/api/admin/chunks").param("q", "학사운영"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].source").value(""))
                .andExpect(jsonPath("$[0].version").value(""))
                .andExpect(jsonPath("$[0].score").value(nullValue()))
                .andExpect(jsonPath("$[0].preview").value(""));
    }

    @Test
    void 빈_검색어는_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/chunks"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("검색어(q)를 입력해 주세요."));
        mockMvc.perform(get("/api/admin/chunks").param("q", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("검색어(q)를 입력해 주세요."));

        verifyNoInteractions(vectorStore);
    }

    @Test
    void topK가_범위를_벗어나면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/chunks").param("q", "졸업").param("topK", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/chunks").param("q", "졸업").param("topK", "6"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("topK는 1 이상 5 이하여야 합니다."));

        verifyNoInteractions(vectorStore);
    }

    @Test
    void topK가_숫자가_아니면_400을_반환한다() throws Exception {
        mockMvc.perform(get("/api/admin/chunks").param("q", "졸업").param("topK", "많이"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("topK는 숫자여야 합니다."));

        verifyNoInteractions(vectorStore);
    }
}
