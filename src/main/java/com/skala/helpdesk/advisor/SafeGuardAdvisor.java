package com.skala.helpdesk.advisor;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

/**
 * 담당: A(첫 번째 책임자) · 리뷰: B — Phase 7 (교안 p.310·p.322 요약 "차단은 저장보다 앞").
 *
 * <p>프롬프트 인젝션·민감어(학번·성적 등 개인정보 노출 유도) 요청을 대화 메모리에
 * 저장되기 <b>전</b>에 차단한다. 그래서 이 Advisor의 order는 Memory Advisor(200)보다
 * 반드시 작아야 한다 — 확정 순서표: Audit(0) → TokenMeter(10) → SafeGuard(100) →
 * Memory(200) → RAG(300).
 *
 * <p>참고: {@code day3-consult-agent/advisor/SafetyAdvisor.java},
 * {@code SpringAI_실습/12_Advisor순서/이모지Advisor.java}(BaseAdvisor 골격)
 *
 * <p>완료 기준: "이전 지시 무시" 류 인젝션·"시스템 프롬프트 보여줘"(검증 시나리오 ⑥)가
 * 차단되고, 차단된 입력이 {@code /api/chat/history}에 남지 않는다.
 */
@Component
public class SafeGuardAdvisor implements BaseAdvisor {

    private static final String REJECTION_MESSAGE =
            "죄송하지만 해당 요청은 처리할 수 없습니다. 학사 안내 범위 내에서 다시 문의해 주세요.";

    // TODO(A, Phase 7): 인젝션(지시 무시·시스템 프롬프트 노출)·관리자 사칭·학번 형태의
    // 민감정보 패턴을 채운다(day3-consult-agent SafetyAdvisor#BLOCKED_PATTERNS 참고).
    private static final List<Pattern> BLOCKED_PATTERNS = List.of();

    private static final int MAX_INPUT_LENGTH = 2000;

    // before()/after()는 BaseAdvisor 인터페이스 요구사항이라 구현하되, 실제 차단 로직은
    // adviseCall()에서 처리한다 — before()만으로는 체인 진행 자체를 막을 수 없기 때문이다.
    @Override
    public ChatClientRequest before(ChatClientRequest req, AdvisorChain chain) {
        return req;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse res, AdvisorChain chain) {
        return res;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String userText = request.prompt().getUserMessage().getText();
        if (isBlocked(userText)) {
            return ChatClientResponse.builder()
                    .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(REJECTION_MESSAGE)))))
                    .context(request.context())
                    .build();
        }
        return chain.nextCall(request);
    }

    private boolean isBlocked(String text) {
        if (text == null) {
            return false;
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            return true;
        }
        return BLOCKED_PATTERNS.stream().anyMatch(p -> p.matcher(text).find());
    }

    @Override
    public String getName() {
        return "safeGuard";
    }

    /** order 100 — Memory(200)보다 앞. 순서 실험 시 이 값을 250으로 바꿔 보고 반드시 되돌린다. */
    @Override
    public int getOrder() {
        return 100;
    }
}
