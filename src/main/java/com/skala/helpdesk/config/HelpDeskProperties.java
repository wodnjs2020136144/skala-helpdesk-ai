package com.skala.helpdesk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 실습에서 조정할 값을 코드가 아니라 설정으로 뺀다 — {@code application.yml}의
 * {@code helpdesk.*} 블록. 담당: A(첫 번째 책임자) · 리뷰: B — Phase 1(p.313).
 * 완성 상태로 제공된다 — 값을 조정하려면 application.yml과 함께 바꾼다.
 *
 * @param rag    RAG 검색 파라미터(top-k·유사도 임계값 — day3-consult-agent 트러블슈팅 참고,
 *               text-embedding-3-small 기준 실측치로 조정한다)
 * @param memory 대화 메모리 윈도우
 * @param tool   도구 호출 안전장치(같은 도구를 무한 호출하는 함정 대비 상한)
 */
@ConfigurationProperties(prefix = "helpdesk")
public record HelpDeskProperties(Rag rag, Memory memory, Tool tool) {

    public record Rag(int topK, double threshold) {}

    public record Memory(int maxMessages) {}

    public record Tool(int maxCalls) {}
}
