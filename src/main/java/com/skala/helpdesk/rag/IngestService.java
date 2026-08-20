package com.skala.helpdesk.rag;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

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
 * <p>참고: {@code SpringAI_실습/ch07_rag}·{@code ch08_ragadv} IngestService,
 * {@code day3-consult-agent/rag/PolicyIngestService.java}, 교안 Phase 2 코드(p.314)
 *
 * <p>완료 기준: {@code helpdesk-docs/*.md} 3종이 인제스트되고, 같은 문서를 다시
 * 인제스트해도 청크가 중복되지 않는다.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final VectorStore vectorStore;

    public IngestService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * TODO(A, Phase 2) — 아래 순서로 채운다.
     * ① {@code vectorStore.delete("source == '" + source + "'")}로 재색인 대비
     * ② {@code TextReader}/{@code TikaDocumentReader}로 읽고 {@code TokenTextSplitter}로 청크 분할
     * ③ {@link #enrich}로 메타데이터를 붙인다
     * ④ {@code vectorStore.add(enriched)}
     */
    public IngestResult ingest(Resource file, String docType, String dept) {
        String source = file.getFilename();
        // TODO(A): 구현 전까지는 아무것도 저장하지 않는다(0건) — bootRun은 되지만 RAG
        // 답변에 근거가 붙지 않는다. 이게 Phase 2 미완료의 정상 신호다.
        return new IngestResult(source, 0);
    }

    /**
     * 기동 시 {@code classpath:helpdesk-docs/*.md} 3종을 자동 인제스트한다. TODO(A)가
     * {@link #ingest}를 채우기 전까지는 0건으로 로그만 남고 앱은 계속 뜬다(교안 원칙 —
     * AI 인프라 실패가 전체 기동을 막지 않는다, CLAUDE.md).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void ingestOnStartup() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:helpdesk-docs/*.md");
            for (Resource file : files) {
                IngestResult result = ingest(file, "academic", "학사팀");
                log.info("[RAG] {} → 청크 {}건", result.source(), result.chunkCount());
            }
        } catch (Exception e) {
            log.error("[RAG] 학사 규정 문서 인제스트 실패 — OPENAI_API_KEY·pgvector 연결을 확인하세요. {}",
                    e.getMessage());
        }
    }

    private Document enrich(Document chunk, String source, String docType, String dept) {
        Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
        metadata.put("source", source);
        metadata.put("docType", docType);
        metadata.put("dept", dept);
        metadata.put("version", "2026-08");
        return new Document(chunk.getText(), metadata);
    }
}
