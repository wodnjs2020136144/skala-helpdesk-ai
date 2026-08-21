package com.skala.helpdesk.rag;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * {@code corpus/README.md})을 얹는다. 청크가 3~6개뿐이던 앵커 코퍼스로는 top-k·threshold
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
     *    {@link TokenTextSplitter}로 청크 분할 — 크기는 {@code helpdesk.rag.*}를 따른다
     * ③ {@link #enrich}로 메타데이터를 붙인다
     * ④ {@code vectorStore.add(enriched)}
     */
    public IngestResult ingest(Resource file, String docType, String dept, String version) {
        String source = file.getFilename();

        vectorStore.delete("source == '" + source + "'");

        List<Document> raw = isPdf(source)
                ? new TikaDocumentReader(file).get()
                : new TextReader(file).get();

        List<Document> chunks = TokenTextSplitter.builder()
                .withChunkSize(properties.rag().chunkSize())
                .withMinChunkSizeChars(properties.rag().minChunkSizeChars())
                .build()
                .apply(raw);

        List<Document> enriched = chunks.stream()
                .map(chunk -> enrich(chunk, source, docType, dept, version))
                .toList();

        vectorStore.add(enriched);
        return new IngestResult(source, enriched.size());
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
            // 각 규정 상세 화면의 실측값이다(corpus/README.md에 취득 경위 기록).
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
