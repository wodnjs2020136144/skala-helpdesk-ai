package com.skala.helpdesk.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.skala.helpdesk.repository.StudentRecordRepository;
import com.skala.helpdesk.repository.WithdrawalRequestRepository;

/**
 * 담당: B(첫 번째 책임자) · 리뷰: A — Phase 4 (교안 p.317).
 *
 * <p>되돌리기 어려운 행동(수강철회)은 접수까지만 도구에 준다. 실행 버튼은 사람이 누른다
 * — 승인 경로는 {@code AdminController}에 있고 도구 목록에는 없어서 모델이 닿을 수 없다
 * (학사운영규정 제3조: 지도교수 승인 후 처리).
 *
 * <p>참고: {@code day3-consult-agent/tools/RefundTools.java},
 * {@code SpringAI_실습/11_승인게이트/SnackTools.java}
 *
 * <p>완료 기준(검증 시나리오 ④): 수강철회가 접수(PENDING)로만 남는다 — 즉시 처리되면 실패다.
 */
@Component
public class RequestTools {

    private final StudentRecordRepository records;
    private final WithdrawalRequestRepository requests;

    public RequestTools(StudentRecordRepository records, WithdrawalRequestRepository requests) {
        this.records = records;
        this.requests = requests;
    }

    // TODO(B, Phase 4): description을 채운다. "즉시 처리되지 않는다"는 문구를 명시해야
    // 모델이 "접수했습니다"로만 답하고 "처리 완료"라고 답하지 않는다.
    @Tool(description = "TODO(B, Phase 4): 수강철회를 접수한다는 것과, 지도교수 승인 후 처리됨을 명시한다.")
    public String requestDrop(@ToolParam(description = "TODO(B): 과목코드 파라미터 설명. 예: CS201") String courseCode,
                              @ToolParam(description = "TODO(B): 철회 사유 파라미터 설명") String reason,
                              ToolContext context) {
        String studentId = (String) context.getContext().get("studentId");
        return records.findByIdAndOwnerId(studentId, studentId)
                .map(r -> {
                    var request = requests.create(studentId, courseCode, reason);
                    return "TODO(B, Phase 4): 접수 완료(신청번호 %s) — 지도교수 승인 후 처리된다는 응답을 정리한다."
                            .formatted(request.no());
                })
                .orElse("해당 학번의 학적 정보를 찾을 수 없습니다.");
    }
}
