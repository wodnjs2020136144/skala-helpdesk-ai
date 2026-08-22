package com.skala.helpdesk.advisor;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.stereotype.Component;

import com.skala.helpdesk.config.AiOpsProperties;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import reactor.core.publisher.Flux;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 8.
 *
 * <p>토큰·지연을 계측한다. {@code ai.tokens}·{@code ai.latency} Micrometer 지표에 태그를
 * 붙여야 기능별로 쪼개 볼 수 있다 — 비용 상한 관리(비기능 요구, p.310)의 근거 데이터가 된다.
 *
 * <p>참고: {@code day3-consult-agent/advisor/TokenMeterAdvisor.java},
 * {@code SpringAI_실습/ch11_advisors/TokenMeterAdvisor.java}
 *
 * <p><b>비용 상한은 여기서 요청을 막지 않는다</b> — {@code budget.maxTotalTokens}를 넘으면
 * {@code ai.budget.exceeded} 카운터를 올리고 WARN 로그만 남긴다(실습·시연 중 설정값을
 * 낮게 잡았다고 갑자기 막히는 걸 피한다). 실제로 차단하려면 이 클래스의 초과 분기 한 곳만
 * 바꾸면 되게 구성했다.
 *
 * <p>완료 기준: {@code GET /actuator/metrics/ai.tokens}·{@code ai.latency}가 쌓인다.
 */
@Component
public class TokenMeterAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger log = LoggerFactory.getLogger(TokenMeterAdvisor.class);

    private final MeterRegistry registry;
    private final AiOpsProperties aiOps;
    private final AtomicLong cumulativeTokens = new AtomicLong();

    public TokenMeterAdvisor(MeterRegistry registry, AiOpsProperties aiOps) {
        this.registry = registry;
        this.aiOps = aiOps;
        registry.gauge("ai.tokens.cumulative", cumulativeTokens);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        try {
            ChatClientResponse response = chain.nextCall(request);
            recordTokens(response);
            registry.timer("ai.latency", Tags.of("outcome", "success"))
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return response;
        } catch (RuntimeException e) {
            registry.timer("ai.latency", Tags.of("outcome", "error"))
                    .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            registry.counter("ai.errors", Tags.of("exception", e.getClass().getSimpleName())).increment();
            throw e;
        }
    }

    /**
     * SSE 스트리밍 경로 계측. {@code CallAdvisor}만 구현하면 스트림 체인은 이 Advisor를
     * 타지 않아 SSE 요청은 토큰·지연·오류가 전혀 쌓이지 않는다(Phase 6 검토 중 발견 —
     * {@code SafeGuardAdvisor}는 {@code BaseAdvisor}라 이 구멍이 없다).
     *
     * <p>{@code Flux.defer}로 요청마다 새 상태(시작 시각·마지막 청크)를 만든다 — 싱글턴
     * 필드에 두면 동시 스트리밍 요청의 시각·usage가 서로 섞인다. usage는 OpenAI 스트리밍
     * 응답의 마지막 청크에만 채워지므로({@code includeUsage} 기본값), 매 청크가 아니라
     * 스트림이 끝났을 때 마지막으로 관찰한 응답 한 번만 집계한다.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        return Flux.defer(() -> {
            long start = System.nanoTime();
            AtomicReference<ChatClientResponse> lastResponse = new AtomicReference<>();
            return chain.nextStream(request)
                    .doOnNext(lastResponse::set)
                    .doOnComplete(() -> {
                        ChatClientResponse last = lastResponse.get();
                        if (last != null) {
                            recordTokens(last);
                        }
                        registry.timer("ai.latency", Tags.of("outcome", "success"))
                                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                    })
                    .doOnError(error -> {
                        registry.timer("ai.latency", Tags.of("outcome", "error"))
                                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                        registry.counter("ai.errors", Tags.of("exception", error.getClass().getSimpleName()))
                                .increment();
                    })
                    .doOnCancel(() -> registry.timer("ai.latency", Tags.of("outcome", "cancelled"))
                            .record(System.nanoTime() - start, TimeUnit.NANOSECONDS));
        });
    }

    /**
     * SafeGuard 거부 응답처럼 실제 모델 호출이 없었던 경우를 건너뛴다. {@code ChatResponseMetadata}
     * 는 기본값이 {@link org.springframework.ai.chat.metadata.EmptyUsage}라 {@code getUsage()}
     * 자체는 null이 아니지만, 토큰 수가 전부 0으로 채워진다 — 이 경우는 계측 대상이 아니다.
     */
    private void recordTokens(ChatClientResponse response) {
        ChatResponseMetadata metadata = response.chatResponse() == null ? null
                : response.chatResponse().getMetadata();
        Usage usage = metadata == null ? null : metadata.getUsage();
        if (usage == null || usage.getTotalTokens() == null || usage.getTotalTokens() == 0) {
            return;
        }

        String model = metadata.getModel() == null ? "unknown" : metadata.getModel();
        Integer promptTokens = usage.getPromptTokens();
        Integer completionTokens = usage.getCompletionTokens();

        if (promptTokens != null && promptTokens > 0) {
            registry.counter("ai.tokens", Tags.of("type", "prompt", "model", model)).increment(promptTokens);
        }
        if (completionTokens != null && completionTokens > 0) {
            registry.counter("ai.tokens", Tags.of("type", "completion", "model", model))
                    .increment(completionTokens);
        }

        long total = cumulativeTokens.addAndGet(usage.getTotalTokens());
        if (total > aiOps.budget().maxTotalTokens()) {
            registry.counter("ai.budget.exceeded").increment();
            log.warn("누적 토큰이 비용 상한을 초과했습니다 cumulative={} limit={}",
                    total, aiOps.budget().maxTotalTokens());
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
