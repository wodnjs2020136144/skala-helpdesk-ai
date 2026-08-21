package com.skala.helpdesk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실습에서 조정할 값을 코드가 아니라 설정으로 뺀다 — {@code application.yml}의
 * {@code helpdesk.*} 블록. 담당: A(첫 번째 책임자) · 리뷰: B — Phase 1(p.313).
 * 완성 상태로 제공된다 — 값을 조정하려면 application.yml과 함께 바꾼다.
 *
 * <p>{@code Rag.chunkSize}·{@code Rag.minChunkSizeChars}는 Phase 2 코퍼스 확장(학사규정
 * PDF 추가) 때 {@code TokenTextSplitter} 파라미터를 코드에 남기지 않기 위해 추가했다 —
 * A가 박성우에게 공유. 시작값은 day3-consult-agent {@code PolicyIngestService}의
 * 실측치(400/200)를 참고했으나, 학사규정은 조항 단위가 짧아 더 작은 값에서 시작한다.
 *
 * @param rag    RAG 검색·인제스트 파라미터(top-k·유사도 임계값·청크 크기 — day3-consult-agent
 *               트러블슈팅 참고, text-embedding-3-small 기준 실측치로 조정한다)
 * @param memory 대화 메모리 윈도우
 * @param tool   도구 호출 안전장치(같은 도구를 무한 호출하는 함정 대비 상한)
 */
@ConfigurationProperties(prefix = "helpdesk")
public record HelpDeskProperties(Rag rag, Memory memory, Tool tool) {

    public record Rag(int topK, double threshold, int chunkSize, int minChunkSizeChars) {}

    public record Memory(int maxMessages) {}

    public record Tool(int maxCalls) {}
}
