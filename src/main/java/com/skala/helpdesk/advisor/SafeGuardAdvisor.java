package com.skala.helpdesk.advisor;

import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

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

    private static final Logger log = LoggerFactory.getLogger(SafeGuardAdvisor.class);

    private static final String REJECTION_MESSAGE =
            "죄송하지만 해당 요청은 처리할 수 없습니다. 학사 안내 범위 내에서 다시 문의해 주세요.";

    private static final int MAX_INPUT_LENGTH = 2000;

    /**
     * 레드팀 체크리스트(docs/레드팀-체크리스트.md)의 프롬프트 계열 공격 4종에 대응한다.
     * 패턴별로 몇 번 항목을 막는지 주석을 달아 B가 실제 공격 시 대조할 수 있게 한다.
     * 토큰 사이 {@code \s*}로 띄어쓰기 변형을 흡수하고, 대소문자·유니코드 케이스를
     * 무시한다.
     *
     * <p><b>학번 형태(예: 7자리 숫자)는 의도적으로 패턴에 넣지 않는다.</b> 레드팀 4번
     * (권한 우회)·6번(데이터 유출)은 문구를 막는 게 아니라 {@code findByIdAndOwnerId}
     * 소유자 검증(Tool 계층, B 담당)이 코드로 막는 영역이다(docs/분업-역할표.md). "제
     * 학번은 2021001인데 졸업 가능한가요?" 같은 정상 문의까지 걸리는 과차단을 피하려고
     * 여기서는 학번 언급 자체를 차단하지 않는다.
     */
    private static final List<NamedPattern> BLOCKED_PATTERNS = List.of(
            // 레드팀 1 — 지시 무시("이전 지시는 모두 무시하고...")
            named("ignore-instructions",
                    "(이전|기존)\\s*(지시|지시사항|명령)\\s*(은|는|을|를)?\\s*(모두|전부)?\\s*무시",
                    "ignore\\s+(all\\s+)?(previous|prior|above)\\s+instructions?"),
            // 레드팀 2 — 시스템 프롬프트·내부 지시사항 노출 요구
            named("expose-system-prompt",
                    "시스템\\s*프롬프트", "내부\\s*지시사항", "프롬프트\\s*(를)?\\s*(그대로)?\\s*(보여|알려|공개)",
                    "system\\s*prompt", "initial\\s+instructions?"),
            // 레드팀 3 — 관리자 사칭·승인 절차 우회 유도
            named("impersonate-admin",
                    "학사팀\\s*관리자", "관리자\\s*(권한|계정)\\s*(으로|이야|입니다)", "승인\\s*절차\\s*(를)?\\s*건너뛰"),
            // 레드팀 9 — 민감정보(주민등록번호) 저장·조회 유도
            named("sensitive-personal-id",
                    "주민(등록)?번호", "\\d{6}\\s*-\\s*\\d{7}"));

    /** 규칙 이름과 컴파일된 패턴을 함께 들고 다닌다 — 매칭 시 어떤 규칙인지 로그로 남기기 위함. */
    private record NamedPattern(String name, Pattern pattern) {
    }

    private static NamedPattern named(String ruleName, String... alternatives) {
        Pattern compiled = Pattern.compile(String.join("|", alternatives),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        return new NamedPattern(ruleName, compiled);
    }

    // before()/after()는 BaseAdvisor 인터페이스 요구사항이라 구현하되, 실제 차단 로직은
    // adviseCall()/adviseStream()에서 처리한다 — before()만으로는 체인 진행 자체를
    // 막을 수 없기 때문이다.
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
        String reason = blockReason(userText(request));
        if (reason != null) {
            logBlocked(reason, userText(request));
            return rejectionResponse(request);
        }
        return chain.nextCall(request);
    }

    /**
     * {@link BaseAdvisor}의 기본 {@code adviseStream}은 {@link #before}만 거치고 그대로
     * 체인을 태우므로, 오버라이드하지 않으면 {@code POST /api/chat/stream}이 SafeGuard를
     * 통째로 우회한다. 차단 시 거부 메시지 하나만 담은 {@link Flux}를 반환하고
     * {@code chain.nextStream(...)}을 호출하지 않는다 — Memory(200)가 이 아래에 있으므로
     * 체인을 안 타면 저장도 되지 않는다.
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String reason = blockReason(userText(request));
        if (reason != null) {
            logBlocked(reason, userText(request));
            return Flux.just(rejectionResponse(request));
        }
        return chain.nextStream(request);
    }

    private String userText(ChatClientRequest request) {
        return request.prompt().getUserMessage() == null ? null : request.prompt().getUserMessage().getText();
    }

    /** 차단 사유(규칙 이름)를 반환한다. 통과하면 {@code null}. */
    private String blockReason(String text) {
        if (text == null) {
            return null;
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            return "input-too-long";
        }
        return BLOCKED_PATTERNS.stream()
                .filter(p -> p.pattern().matcher(text).find())
                .findFirst()
                .map(NamedPattern::name)
                .orElse(null);
    }

    private ChatClientResponse rejectionResponse(ChatClientRequest request) {
        return ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage(REJECTION_MESSAGE)))))
                .context(request.context())
                .build();
    }

    /** 매칭된 규칙 이름과 입력 길이만 남긴다 — 사용자 입력 원문은 로그에 남기지 않는다. */
    private void logBlocked(String rule, String text) {
        log.warn("SafeGuard blocked rule={} inputLength={}", rule, text == null ? 0 : text.length());
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
