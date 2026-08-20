package com.skala.helpdesk.eval;

import java.util.List;

/**
 * 담당: B(첫 번째 책임자) · 리뷰: A — Phase 8.
 *
 * <p>품질 기준선 — 정답이 정해진 질문 세트를 만들어 두고, 규정이 바뀌거나 프롬프트를
 * 수정할 때마다 답이 여전히 맞는지 확인한다. 골든셋 실행은 실제 모델을 호출하므로
 * 기본 테스트에서는 제외하고 별도 태스크로 돌린다(교안 p.225 패턴,
 * {@code 사내문서QnA_메인실습}의 {@code Lab2GoldenSetTest} 참고).
 *
 * <p>TODO(B, Phase 8): 아래를 채운다.
 * <ul>
 *   <li>질문 20종 — 학사운영규정·졸업요건·장학금 규정에서 고르게 뽑는다</li>
 *   <li>기대 근거 문서(source) — 어떤 규정 파일에서 답이 나와야 하는지</li>
 *   <li>실행기 — {@code HelpDeskService.ask(...)}를 호출해 sources에 기대 문서가
 *       포함되는지 검증한다</li>
 * </ul>
 *
 * <p>완료 기준: P95 응답시간·Tool 성공률/오류율(비기능 요구, p.310)을 함께 기록한다.
 */
public record GoldenSet(List<Case> cases) {

    public record Case(String question, String expectedSource) {
    }

    // TODO(B, Phase 8): 아래는 자리표시용 빈 셋이다 — 20종을 채운다.
    public static GoldenSet placeholder() {
        return new GoldenSet(List.of());
    }
}
