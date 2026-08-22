package com.skala.helpdesk.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

import com.skala.helpdesk.config.HelpDeskProperties;

/**
 * 담당: A. 부칙(개정 이력의 시행일·경과조치)이 운영 검색에 섞여 현행 규정과 뒤엉킨 답이
 * 나오는 문제를 막기 위해, 인제스트 단계에서 본칙/부칙을 갈라 {@code section} 메타데이터를
 * 붙이는지 확인한다({@code IngestService.splitBySection} Javadoc의 26학점 실측 사례).
 */
class IngestServiceTest {

    @Test
    void 부칙이_있는_문서는_본칙과_부칙으로_갈려_section이_붙는다(@TempDir Path dir) throws IOException {
        VectorStore vectorStore = mock(VectorStore.class);
        IngestService service = new IngestService(vectorStore, properties());
        Resource file = write(dir, "규칙.md", """
                제12조(수강신청 제한) ① 수강신청 상한 학점은 다음 각 호와 같다.
                1. 공학사과정: 21학점 이내
                ② 다음 각 호에 해당하는 학점은 제1항의 상한학점을 초과하여 신청할 수 있다.
                1. 직전학기 성적 평점평균 3.5이상 취득자: 3학점

                부 칙
                제2조(수강신청학점제한의 특례) 1999학년도 이전의 160졸업학점제 교과과정
                이수대상자는 매 정규학기에 23학점까지 수강신청 할 수 있으며, 직전학기 성적의
                평점평균이 3.5이상인 자는 1학점을 추가신청 할 수 있다.
                """);

        service.ingest(file, "교무행정", "학사팀", "2026-04-01");

        List<Document> saved = captureSaved(vectorStore);
        assertThat(saved).isNotEmpty();
        assertThat(saved).allSatisfy(chunk ->
                assertThat(chunk.getMetadata()).containsKey(IngestService.SECTION));

        // 본칙 조문은 main, 부칙 특례는 supplementary로 갈린다.
        assertThat(sectionOfChunkContaining(saved, "공학사과정")).isEqualTo(IngestService.MAIN);
        assertThat(sectionOfChunkContaining(saved, "1999학년도")).isEqualTo(IngestService.SUPPLEMENTARY);
    }

    @Test
    void 꺾쇠로_감싼_부칙_헤더도_경계로_인식한다(@TempDir Path dir) throws IOException {
        // 학칙 PDF는 다른 규정과 달리 "<부 칙>"으로 표기한다(실측) — 꺾쇠를 빠뜨리면
        // 학칙만 분리되지 않아 폐지된 옛 조항이 운영 검색에 그대로 남는다.
        VectorStore vectorStore = mock(VectorStore.class);
        IngestService service = new IngestService(vectorStore, properties());
        Resource file = write(dir, "학칙.md", """
                제58조(졸업) 본교의 소정 과정을 이수한 자에게는 학위를 수여한다.
                제59조(위임사항) 이 학칙의 시행에 필요한 사항은 총장이 따로 정한다.

                <부 칙>
                제1조(시행일) 이 학칙은 2021년 3월 31일부터 시행한다.
                제2조(경과조치) 종전의 규정에 의하여 시행된 사항은 이 학칙에 의한 것으로 본다.
                """);

        service.ingest(file, "학칙", "교무팀", "2026-03-31");

        List<Document> saved = captureSaved(vectorStore);
        assertThat(sectionOfChunkContaining(saved, "제58조")).isEqualTo(IngestService.MAIN);
        assertThat(sectionOfChunkContaining(saved, "2021년 3월 31일"))
                .isEqualTo(IngestService.SUPPLEMENTARY);
    }

    @Test
    void 부칙이_없는_문서는_전부_본칙으로_둔다(@TempDir Path dir) throws IOException {
        VectorStore vectorStore = mock(VectorStore.class);
        IngestService service = new IngestService(vectorStore, properties());
        Resource file = write(dir, "졸업요건.md", """
                ## 졸업 요건
                1. 총 이수학점은 130학점 이상이어야 한다.
                2. 졸업논문 또는 캡스톤 프로젝트 3학점을 이수하여야 한다.
                3. 어학 요건은 TOEIC 700점 이상으로 한다.
                """);

        service.ingest(file, "academic", "학사팀", "2026-08");

        assertThat(captureSaved(vectorStore)).isNotEmpty().allSatisfy(chunk ->
                assertThat(chunk.getMetadata()).containsEntry(IngestService.SECTION, IngestService.MAIN));
    }

    @Test
    void 조문_안의_부칙_참조는_경계로_삼지_않는다(@TempDir Path dir) throws IOException {
        // "부칙 제2조의 규정에 의한다"처럼 줄 중간에서 다른 조항을 가리키는 참조는
        // 부칙의 시작이 아니다 — 여기서 잘리면 본칙이 통째로 검색에서 빠진다.
        VectorStore vectorStore = mock(VectorStore.class);
        IngestService service = new IngestService(vectorStore, properties());
        Resource file = write(dir, "참조.md", """
                제34조(편입학자의 교과과정 적용) 편입학자의 수강신청 제한학점에 대하여는
                부칙 제2조의 규정에 의한 것으로 본다. 그 밖의 사항은 총장이 따로 정한다.
                제35조(수강신청 정정) 수강신청 정정은 개강 후 1주 이내에 하여야 한다.
                """);

        service.ingest(file, "교무행정", "학사팀", "2026-04-01");

        assertThat(captureSaved(vectorStore)).isNotEmpty().allSatisfy(chunk ->
                assertThat(chunk.getMetadata()).containsEntry(IngestService.SECTION, IngestService.MAIN));
    }

    @Test
    void 페이지_머리말과_꼬리말을_지워_조문이_끊기지_않게_한다(@TempDir Path dir) throws IOException {
        // Tika는 페이지 머리말·꼬리말을 본문과 섞어 뽑는다. 조문 한가운데 끼면 청크
        // 임베딩이 흐려져 정작 정답 조문이 검색 상위에 들지 못한다(실측).
        VectorStore vectorStore = mock(VectorStore.class);
        IngestService service = new IngestService(vectorStore, properties());
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= 6; page++) {
            text.append("학사운영에 관한 규칙\n")
                    .append("- %d / 6 -\n".formatted(page))
                    .append("제%d조(수강신청 제한) 상한 학점은 공학사과정 21학점 이내로 한다.\n".formatted(page));
        }
        Resource file = write(dir, "규칙.md", text.toString());

        service.ingest(file, "교무행정", "학사팀", "2026-04-01");

        String merged = captureSaved(vectorStore).stream()
                .map(Document::getText)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(merged)
                .doesNotContain("학사운영에 관한 규칙")   // 6회 반복된 짧은 머리말
                .doesNotContain("- 1 / 6 -")            // 페이지 꼬리말
                .contains("공학사과정 21학점 이내");      // 조문 본문은 남는다
    }

    @Test
    void 재색인을_위해_문서_단위_삭제를_먼저_한다(@TempDir Path dir) throws IOException {
        VectorStore vectorStore = mock(VectorStore.class);
        IngestService service = new IngestService(vectorStore, properties());
        Resource file = write(dir, "규정.md", "제1조(목적) 이 규칙은 학사운영에 필요한 사항을 정한다.");

        service.ingest(file, "academic", "학사팀", "2026-08");

        InOrder order = inOrder(vectorStore);
        order.verify(vectorStore).delete("source == '규정.md'");
        order.verify(vectorStore).add(anyList());
    }

    // --- 테스트 헬퍼 ---

    private static HelpDeskProperties properties() {
        return new HelpDeskProperties(
                new HelpDeskProperties.Rag(5, 50, 0.3, 300, 150),
                new HelpDeskProperties.Memory(20),
                new HelpDeskProperties.Tool(5));
    }

    private static Resource write(Path dir, String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return new FileSystemResource(file);
    }

    @SuppressWarnings("unchecked")
    private static List<Document> captureSaved(VectorStore vectorStore) {
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).delete(anyString());
        verify(vectorStore).add(captor.capture());
        return captor.getValue();
    }

    private static String sectionOfChunkContaining(List<Document> chunks, String needle) {
        return chunks.stream()
                .filter(chunk -> chunk.getText() != null && chunk.getText().contains(needle))
                .findFirst()
                .map(chunk -> String.valueOf(chunk.getMetadata().get(IngestService.SECTION)))
                .orElseThrow(() -> new AssertionError("'%s'를 담은 청크가 없다".formatted(needle)));
    }
}
