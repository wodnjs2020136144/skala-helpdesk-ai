package com.skala.helpdesk.advisor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.stereotype.Component;

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
public class AuditAdvisor implements CallAdvisor {

    private static final Logger audit = LoggerFactory.getLogger("AI_TOOL_AUDIT");

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        long start = System.currentTimeMillis();
        try {
            ChatClientResponse response = chain.nextCall(request);
            // TODO(B, Phase 7): 사용자·질문·응답 길이·소요시간을 감사 로그에 남긴다.
            // 학번·성적 등 개인정보가 원문 그대로 로그에 남지 않도록 마스킹한다
            // (day3-consult-agent ToolAuditAspect#mask 패턴 참고).
            audit.info("chat call ok elapsedMs={}", System.currentTimeMillis() - start);
            return response;
        } catch (RuntimeException e) {
            audit.warn("chat call failed elapsedMs={} error={}",
                    System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
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
}
