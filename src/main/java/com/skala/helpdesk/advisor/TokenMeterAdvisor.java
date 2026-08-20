package com.skala.helpdesk.advisor;

import java.util.concurrent.TimeUnit;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 8.
 *
 * <p>토큰·지연을 계측한다. {@code ai.tokens}·{@code ai.latency} Micrometer 지표에 태그를
 * 붙여야 기능별로 쪼개 볼 수 있다 — 비용 상한 관리(비기능 요구, p.310)의 근거 데이터가 된다.
 *
 * <p>참고: {@code day3-consult-agent/advisor/TokenMeterAdvisor.java},
 * {@code SpringAI_실습/ch11_advisors/TokenMeterAdvisor.java}
 *
 * <p>완료 기준: {@code GET /actuator/metrics/ai.tokens}·{@code ai.latency}가 쌓인다.
 */
@Component
public class TokenMeterAdvisor implements CallAdvisor {

    private final MeterRegistry registry;

    public TokenMeterAdvisor(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        try {
            ChatClientResponse response = chain.nextCall(request);
            // TODO(A, Phase 8): response.chatResponse().getMetadata().getUsage()에서
            // promptTokens·completionTokens를 꺼내 registry.counter("ai.tokens", "type", ...)에
            // 기록한다(day3-consult-agent TokenMeterAdvisor#recordTokens 참고).
            return response;
        } finally {
            registry.timer("ai.latency").record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @Override
    public String getName() {
        return "tokenMeter";
    }

    /** order 10 — 감사(0) 다음으로 바깥. 계측은 바깥쪽에 둬야 안쪽 전체 시간이 잡힌다. */
    @Override
    public int getOrder() {
        return 10;
    }
}
