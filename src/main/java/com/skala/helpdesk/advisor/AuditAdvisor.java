package com.skala.helpdesk.advisor;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Component;

import com.skala.helpdesk.chat.HelpDeskService;
import com.skala.helpdesk.web.TraceIdFilter;

import reactor.core.publisher.Flux;

/**
 * 담당: B(첫 번째 책임자) · 리뷰: A.
 * Phase 1(p.313) — 골격만 등록해 {@code AiConfig} 조립이 컴파일되게 한다.
 * Phase 7(p.317·p.318) — 도구 호출 감사·개인정보 마스킹을 완성한다.
 *
 * <p>day3-consult-agent는 도구 호출 감사를 AOP({@code audit/ToolAuditAspect.java})로
 * 구현했다 — {@code @Tool} 메서드 호출을 가로채는 방식이라 어떤 도구를 쓰든 빠짐없이
 * 잡힌다. 이 Advisor는 채팅 호출 단위 감사(질문·응답 길이, 지연, 성공 여부)를 담당한다.
 * <b>도구 호출 감사까지 이 Advisor 안에서 처리할지, day3처럼 별도 AOP Aspect를 추가할지는
 * B가 Phase 7에서 정한다.</b>
 *
 * <p>참고: {@code SpringAI_실습/ch10_toolsafe/ToolAuditAspect.java},
 * {@code day3-consult-agent/audit/ToolAuditAspect.java}
 *
 * <p>완료 기준: 모든 요청·도구 호출의 성공/실패가 감사 로그에 남는다. 학번·성적 등
 * 개인정보가 로그에 원문 그대로 남지 않는다(마스킹).
 */
@Component
public class AuditAdvisor implements CallAdvisor, StreamAdvisor {

    private static final Logger audit = LoggerFactory.getLogger("AI_TOOL_AUDIT");

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.nanoTime();
        AuditIdentity identity = identity(request);
        try {
            ChatClientResponse response = chain.nextCall(request);
            audit.info("chat call ok traceId={} user={} responseChars={} elapsedMs={}",
                    identity.traceId(), identity.maskedUser(), responseChars(response), elapsedMillis(start));
            return response;
        } catch (RuntimeException e) {
            audit.warn("chat call failed traceId={} user={} elapsedMs={} errorType={}",
                    identity.traceId(), identity.maskedUser(), elapsedMillis(start),
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        long start = System.nanoTime();
        AuditIdentity identity = identity(request);
        return chain.nextStream(request)
                .doOnComplete(() -> audit.info(
                        "chat stream ok traceId={} user={} elapsedMs={}",
                        identity.traceId(), identity.maskedUser(), elapsedMillis(start)))
                .doOnError(error -> audit.warn(
                        "chat stream failed traceId={} user={} elapsedMs={} errorType={}",
                        identity.traceId(), identity.maskedUser(), elapsedMillis(start),
                        error.getClass().getSimpleName()))
                .doOnCancel(() -> audit.info(
                        "chat stream cancelled traceId={} user={} elapsedMs={}",
                        identity.traceId(), identity.maskedUser(), elapsedMillis(start)));
    }

    @Override
    public String getName() {
        return "audit";
    }

    /** 가장 바깥(order 0) — 실패한 호출까지 전부 잡아야 한다. */
    @Override
    public int getOrder() {
        return 0;
    }

    private AuditIdentity identity(ChatClientRequest request) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        Object conversationId = request.context().get(ChatMemory.CONVERSATION_ID);
        return new AuditIdentity(traceId, maskedUser(conversationId));
    }

    private String maskedUser(Object conversationId) {
        if (!(conversationId instanceof String value)) {
            return "unknown";
        }
        String studentId = HelpDeskService.studentIdFrom(value);
        if ("unknown".equals(studentId) || studentId.isBlank()) {
            return "unknown";
        }
        int visible = Math.min(3, studentId.length());
        return studentId.substring(0, visible) + "*".repeat(studentId.length() - visible);
    }

    private int responseChars(ChatClientResponse response) {
        if (response == null || response.chatResponse() == null
                || response.chatResponse().getResult() == null
                || response.chatResponse().getResult().getOutput() == null
                || response.chatResponse().getResult().getOutput().getText() == null) {
            return 0;
        }
        return response.chatResponse().getResult().getOutput().getText().length();
    }

    private long elapsedMillis(long start) {
        return (System.nanoTime() - start) / 1_000_000;
    }

    private record AuditIdentity(String traceId, String maskedUser) {
    }
}
