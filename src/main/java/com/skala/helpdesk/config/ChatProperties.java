package com.skala.helpdesk.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 6(p.320) SSE 타임아웃.
 *
 * <p>{@code HelpDeskProperties}는 AGENTS.md의 "완성 상태로 제공되는 파일 — 손대지
 * 않는다" 목록에 있고, 이미 예외가 2건(chunkSize·inspectionMaxTopK) 누적돼 "다른 필드를
 * 더 추가하려면 먼저 알린다"고 명시돼 있다. {@link AiOpsProperties}(Phase 8)와 같은 이유로
 * 별도 클래스로 분리한다 — "값을 코드 상수로 남기지 않고 설정으로 뺀다"는 규약 취지는
 * 그대로 지킨다. {@code @ConfigurationPropertiesScan}(HelpDeskApplication)이 자동으로
 * 빈 등록한다.
 *
 * <p>이 타임아웃은 {@code ChatController}(B)가 주입해 SSE 응답의 {@code .timeout()}에
 * 적용한다 — A는 값만 바인딩해 제공하고, 실제 적용 지점은 B 소유다.
 *
 * @param streamTimeout SSE 스트림 전체에 걸리는 타임아웃(검증 시나리오 완료 기준의
 *                       "60초 타임아웃 처리 지원" 항목)
 */
@ConfigurationProperties(prefix = "helpdesk.chat")
public record ChatProperties(Duration streamTimeout) {
}
