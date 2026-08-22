package com.skala.helpdesk.rag;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import com.skala.helpdesk.config.HelpDeskProperties;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 2 (교안 p.314). Phase 2 심화(p.315)는
 * {@code AdminController}(B)의 청크 검사 API로 확인한다.
 *
 * <p>학사 규정 문서(학사운영규정·졸업요건·장학금 규정)를 읽어 청크로 나누고 메타데이터를
 * 붙여 pgvector에 저장한다. 같은 문서를 다시 넣으면 중복된다 — <b>문서 단위 삭제 후
 * 재삽입</b>한다. 출처 표기를 위해 {@code source}·{@code docType}·{@code dept}·
 * {@code version}을 반드시 넣는다(Phase 3에서 A가 이 메타데이터로 출처를 꺼낸다).
 *
 * <p>⚠️ 함정(교안 p.314): 재색인 없이 add만 반복하면 같은 청크가 쌓여 검색 결과가 같은
 * 문장으로 도배된다 — 문서 단위 삭제를 먼저 한다.
 *
 * <p><b>코퍼스 확장(Phase 2 심화)</b> — {@code helpdesk-docs/*.md} 앵커 3종(Tool 반환값과
 * 정합성이 맞춰져 있어 교체하지 않는다)에 더해, {@code helpdesk-docs/regulations/*.pdf}로
 * 실제 학사규정 3종(학칙·학사운영에관한규칙·장학금에관한규칙, 출처는
 * {@code helpdesk-docs/regulations/README.md})을 얹는다. 청크가 3~6개뿐이던 앵커 코퍼스로는 top-k·threshold
 * 튜닝도, {@code docType}·{@code dept} 필터링도, 유사 조항(휴학/자퇴/제적) 검색 실패도
 * 관찰할 수 없었다 — 규정 3종을 더해야 이 학습 지점이 살아난다. PDF는
 * {@link TikaDocumentReader}로, md는 기존대로 {@link TextReader}로 읽는다(Tika는 HWP를
 * 읽지 못하므로 원본은 반드시 PDF 다운로드본을 쓴다).
 *
 * <p>참고: {@code SpringAI_실습/ch07_rag}·{@code ch08_ragadv} IngestService,
 * {@code day3-consult-agent/rag/PolicyIngestService.java}, 교안 Phase 2 코드(p.314)
 *
 * <p>완료 기준: {@code helpdesk-docs/*.md} 3종과 {@code helpdesk-docs/regulations/*.pdf}
 * 3종이 인제스트되고, 같은 문서를 다시 인제스트해도 청크가 중복되지 않는다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    /** 본칙/부칙을 가르는 메타데이터 키 — 운영 검색은 {@link #MAIN}만 본다. */
    public static final String SECTION = "section";
    public static final String MAIN = "main";
    public static final String SUPPLEMENTARY = "supplementary";

    /**
     * 부칙 시작 표시. 줄 전체가 부칙 헤더인 경우만 잡는다({@link #splitBySection} 참고) —
     * 조문 안의 "부칙 제2조" 같은 <i>참조</i>는 줄 전체를 차지하지 않아 걸리지 않는다.
     *
     * <p>문서마다 표기가 다르다(실측): {@code 학사운영에관한규칙.pdf}·
     * {@code 장학금에관한규칙.pdf}는 {@code "부 칙"}인데 {@code 한국기술교육대학교_학칙.pdf}는
     * {@code "<부 칙>"}처럼 꺾쇠로 감싼다. 꺾쇠를 빠뜨렸다가 학칙 PDF만 분리되지 않는 것을
     * 재인제스트 실측에서 발견해 선택적으로 허용하도록 넓혔다.
     */
    private static final Pattern SUPPLEMENTARY_HEADER =
            Pattern.compile("(?m)^[ \\t]*<?[ \\t]*부[ \\t]*칙[ \\t]*>?[ \\t]*$");

    /** 페이지 꼬리말 — {@code - 4 / 63 -} 꼴({@link #stripPageFurniture} 참고). */
    private static final Pattern PAGE_FOOTER =
            Pattern.compile("(?m)^[ \\t]*-[ \\t]*\\d+[ \\t]*/[ \\t]*\\d+[ \\t]*-[ \\t]*$");

    /** 이 길이 이하이면서 */
    private static final int FURNITURE_MAX_CHARS = 30;

    /** 이 횟수 이상 되풀이되는 줄을 페이지 머리말로 본다. */
    private static final long FURNITURE_MIN_REPEAT = 5;

    private final VectorStore vectorStore;
    private final HelpDeskProperties properties;

    public IngestService(VectorStore vectorStore, HelpDeskProperties properties) {
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    /**
     * 문서 한 건을 읽어 청크로 나누고 메타데이터를 붙여 저장한다.
     * ① {@code vectorStore.delete("source == '" + source + "'")}로 재색인 대비
     * ② 확장자별 리더(.md → {@link TextReader}, .pdf → {@link TikaDocumentReader})로 읽고
     * ③ {@link #splitBySection}으로 본칙/부칙을 가른 뒤
     *    {@link TokenTextSplitter}로 청크 분할 — 크기는 {@code helpdesk.rag.*}를 따른다
     * ④ {@link #enrich}로 메타데이터를 붙인다
     * ⑤ {@code vectorStore.add(enriched)}
     */
    public IngestResult ingest(Resource file, String docType, String dept, String version) {
        String source = file.getFilename();

        vectorStore.delete("source == '" + source + "'");

        List<Document> raw = isPdf(source)
                ? new TikaDocumentReader(file).get()
                : new TextReader(file).get();

        List<Document> sectioned = raw.stream()
                .flatMap(document -> splitBySection(document).stream())
                .toList();

        List<Document> chunks = TokenTextSplitter.builder()
                .withChunkSize(properties.rag().chunkSize())
                .withMinChunkSizeChars(properties.rag().minChunkSizeChars())
                .build()
                .apply(sectioned);

        List<Document> enriched = chunks.stream()
                .map(chunk -> enrich(chunk, source, docType, dept, version))
                .toList();

        vectorStore.add(enriched);
        return new IngestResult(source, enriched.size());
    }

    /**
     * 본칙과 부칙을 갈라 {@link #SECTION} 메타데이터를 붙인다. <b>청크 분할 전에</b>
     * 나눠야 한다 — {@code TextSplitter}는 부모 문서의 메타데이터를 잘린 청크마다 그대로
     * 복사하므로(Spring AI 2.0 {@code TextSplitter.createDocuments} 확인), 여기서 붙인
     * {@code section}이 모든 청크에 전파된다.
     *
     * <p><b>왜 나누는가</b> — 부칙(개정 이력의 시행일·경과조치)이 운영 검색에 섞이면 현행
     * 규정과 뒤엉킨 답이 나온다. 실측한 사례: "한 학기에 최대 몇 학점까지 신청할 수
     * 있나요?"에 모델이 <i>"일반적으로 23학점 … 3.5 이상이면 3학점 추가 … 최대 26학점"</i>
     * 이라고 답했다. 23학점은 부칙 제2조가 <b>1999학년도 이전 160졸업학점제 대상자</b>에게만
     * 주는 특례고, 현행 본칙 제12조는 공학사과정 21학점(+성적우수자 3학점 = 24학점)이다.
     * 두 조항이 뒤섞여 어느 규정에도 없는 26학점이 나왔다. 검색 결과를 보니 정답인 본칙
     * ①항은 top-5에 들지 못하고 부칙이 그 위에 잡히고 있었다.
     *
     * <p>판정은 Tika 추출 결과 기준이다 — PDF 원문은 "부"와 "칙"이 세로로 조판돼 있지만
     * {@link TikaDocumentReader}는 {@code "부 칙"} 한 줄로 정리해 준다(실측 확인). 조문
     * 안에서 다른 조항을 가리키는 "부칙 제2조" 같은 <i>참조</i>는 줄 전체를 차지하지 않으므로
     * 이 패턴에 걸리지 않는다. 부칙이 없는 문서(md 앵커 3종)는 통째로 본칙으로 둔다.
     *
     * <p>부칙을 버리지는 않는다 — {@code supplementary}로 저장해 두고 운영 검색
     * ({@code AiConfig}의 {@code QuestionAnswerAdvisor})에서만 뺀다. 관리자 진단
     * ({@code AdminController})은 필터 없이 조회하므로 부칙까지 그대로 볼 수 있다.
     */
    private List<Document> splitBySection(Document document) {
        String text = document.getText();
        if (text == null || text.isBlank()) {
            return List.of(withSection(document, text, MAIN));
        }
        Matcher matcher = SUPPLEMENTARY_HEADER.matcher(text);
        if (!matcher.find()) {
            return List.of(withSection(document, text, MAIN));
        }
        int boundary = matcher.start();
        return List.of(
                withSection(document, text.substring(0, boundary), MAIN),
                withSection(document, text.substring(boundary), SUPPLEMENTARY));
    }

    /**
     * 섹션을 나눈 뒤 <b>그 다음에</b> 페이지 머리말·꼬리말을 지운다. 순서가 중요하다 —
     * 부칙 헤더("부 칙")도 문서에 수십 번 반복되는 짧은 줄이라, 먼저 지우면
     * {@link #splitBySection}이 경계를 찾지 못한다.
     *
     * <p><b>왜 지우는가</b> — Tika는 PDF의 페이지 머리말·꼬리말을 본문과 섞어 뽑는다.
     * 그 결과 조문 하나가 이렇게 끊긴다(실측):
     * <pre>
     * 제12조(수강신청 제한) ① 수강신청 상한 학점은 다음 각 호와 같다. (개정 2021. 4. 1., 2022.
     * 학사운영에 관한 규칙      &lt;- 페이지 머리말
     * - 4 / 63 -               &lt;- 페이지 꼬리말
     * 3. 1.)                   &lt;- 잘린 개정일 파편
     * 1. 공학사과정: 21학점 이내
     * </pre>
     * 조문 한가운데 관계없는 문자열이 끼면 청크 임베딩이 흐려져, "한 학기에 최대 몇
     * 학점까지 신청할 수 있나요?"라는 질문에 정작 이 청크가 상위 검색 결과에 들지 못했다.
     *
     * <p>꼬리말은 {@code - 4 / 63 -} 꼴이라 패턴으로 지운다. 머리말은 문서마다 제목이
     * 달라 패턴으로 못 잡으므로, <b>짧으면서 문서 전체에 여러 번 되풀이되는 줄</b>을
     * 머리말로 본다({@link #FURNITURE_MAX_CHARS}자 이하 · {@link #FURNITURE_MIN_REPEAT}회
     * 이상). 조문 본문은 이만큼 짧으면서 똑같이 반복되지 않는다.
     */
    private String stripPageFurniture(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String withoutFooter = PAGE_FOOTER.matcher(text).replaceAll("");

        List<String> lines = withoutFooter.lines().toList();
        Map<String, Long> repeats = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && trimmed.length() <= FURNITURE_MAX_CHARS) {
                repeats.merge(trimmed, 1L, Long::sum);
            }
        }
        return lines.stream()
                .filter(line -> repeats.getOrDefault(line.strip(), 0L) < FURNITURE_MIN_REPEAT)
                .collect(Collectors.joining("\n"));
    }

    private Document withSection(Document document, String text, String section) {
        Map<String, Object> metadata = new HashMap<>(document.getMetadata());
        metadata.put(SECTION, section);
        return new Document(stripPageFurniture(text), metadata);
    }

    private boolean isPdf(String source) {
        return source != null && source.toLowerCase().endsWith(".pdf");
    }

    /**
     * 기동 시 앵커 문서(md 3종)와 확장 코퍼스(학사규정 PDF 3종)를 자동 인제스트한다.
     * 실패해도 앱은 계속 뜬다(교안 원칙 — AI 인프라 실패가 전체 기동을 막지 않는다,
     * CLAUDE.md).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        try {
            ingestGlob("classpath:helpdesk-docs/*.md", "academic", "학사팀", "2026-08");

            // Phase 2 심화 — rule.koreatech.ac.kr(한국기술교육대학교 내규관리시스템)에서
            // PDF로 받은 학사규정 3종. docType(분야 트리)·dept(담당부서)·version(개정일)은
            // 각 규정 상세 화면의 실측값이다(helpdesk-docs/regulations/README.md에 취득 경위 기록).
            ingestFile("classpath:helpdesk-docs/regulations/한국기술교육대학교_학칙.pdf",
                    "학칙", "교무팀", "2026-03-31");
            ingestFile("classpath:helpdesk-docs/regulations/학사운영에관한규칙.pdf",
                    "교무행정", "학사팀", "2026-04-01");
            ingestFile("classpath:helpdesk-docs/regulations/장학금에관한규칙.pdf",
                    "학생행정", "학생지원팀", "2026-05-01");
        } catch (Exception e) {
            log.error("[RAG] 학사 문서 인제스트 실패 — OPENAI_API_KEY·pgvector 연결을 확인하세요. {}",
                    e.getMessage());
        }
    }

    private void ingestGlob(String pattern, String docType, String dept, String version) throws IOException {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources(pattern);
        for (Resource file : files) {
            IngestResult result = ingest(file, docType, dept, version);
            log.info("[RAG] {} → 청크 {}건", result.source(), result.chunkCount());
        }
    }

    private void ingestFile(String location, String docType, String dept, String version) {
        Resource file = new PathMatchingResourcePatternResolver().getResource(location);
        IngestResult result = ingest(file, docType, dept, version);
        log.info("[RAG] {} → 청크 {}건", result.source(), result.chunkCount());
    }

    private Document enrich(Document chunk, String source, String docType, String dept, String version) {
        Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
        metadata.put("source", source);
        metadata.put("docType", docType);
        metadata.put("dept", dept);
        metadata.put("version", version);
        return new Document(chunk.getText(), metadata);
    }
}
