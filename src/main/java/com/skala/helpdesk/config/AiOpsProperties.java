package com.skala.helpdesk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 8(모델 폴백 · 비용 상한).
 *
 * <p>{@code HelpDeskProperties}는 AGENTS.md의 "완성 상태로 제공되는 파일 — 손대지
 * 않는다" 목록에 있고, 이미 예외가 2건(chunkSize·inspectionMaxTopK) 누적돼 "다른 필드를
 * 더 추가하려면 먼저 알린다"고 명시돼 있다. Phase 8 운영 설정(폴백 모델·비용 상한)은 B의
 * 응답을 기다리지 않기 위해 별도 클래스로 분리한다 — "상수를 코드에 남기지 않는다"는
 * 규약 취지는 그대로 지킨다. {@code @ConfigurationPropertiesScan}(HelpDeskApplication)이
 * 자동으로 빈 등록한다.
 *
 * @param fallback 주 모델 장애 시 대체 모델(검증 시나리오 ⑦)
 * @param budget   누적 토큰 상한 — 초과해도 요청을 막지 않고 경고 로그·지표만 남긴다
 *                 (실습 중 갑자기 막히는 것을 피하기 위함, TokenMeterAdvisor 참고)
 */
@ConfigurationProperties(prefix = "helpdesk.ai")
public record AiOpsProperties(Fallback fallback, Budget budget) {

    public record Fallback(boolean enabled, String model) {
    }

    public record Budget(long maxTotalTokens) {
    }
}
