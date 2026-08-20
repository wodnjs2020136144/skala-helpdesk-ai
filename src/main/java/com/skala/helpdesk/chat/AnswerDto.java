package com.skala.helpdesk.chat;

import java.util.List;

/**
 * 공동 책임 — A·B 둘 다 이 계약을 바꾸려면 합의한다(docs/분업-역할표.md "공동 책임").
 * 담당(파일 소유): B / A가 RAG 출처 구조를 검토한다.
 *
 * <p>화면이 쓰기 좋게 답변·출처·도구사용 여부를 나눠 반환한다(교안 p.320).
 */
public record AnswerDto(String answer, List<Source> sources, boolean toolUsed) {

    /** RAG 근거 문서 1건의 출처. Phase 3(A)가 {@code QuestionAnswerAdvisor}의 검색 결과에서 채운다. */
    public record Source(String document, String version) {
    }
}
