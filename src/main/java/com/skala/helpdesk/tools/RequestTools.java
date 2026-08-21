package com.skala.helpdesk.tools;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.skala.helpdesk.domain.EnrollmentStatus;
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

    @Tool(description = "인증된 학생 본인의 현재 수강 과목에 대한 수강철회를 접수한다. "
            + "사용자가 '수강철회', '과목 취소'를 요청하면 과목 코드뿐 아니라 과목명으로 말해도 반드시 사용한다. "
            + "접수만 생성하며 "
            + "즉시 처리되지 않고 PENDING 상태로 남는다. 실제 철회는 지도교수·학사팀 승인 후 처리된다.")
    public String requestDrop(@ToolParam(description = "철회할 과목 코드 또는 과목명. 예: CS201, 알고리즘") String courseCode,
                              @ToolParam(description = "수강철회 사유. 예: 개인 일정 변경") String reason,
                              ToolContext context) {
        markToolUsed(context);
        String studentId = studentId(context);
        if (studentId == null) {
            return "해당 학번의 학적 정보를 찾을 수 없습니다.";
        }
        if (courseCode == null || courseCode.isBlank()) {
            return "해당 수강 과목을 찾을 수 없습니다.";
        }
        if (reason == null || reason.isBlank()) {
            return "수강철회 사유를 입력해 주세요.";
        }

        String courseIdentifier = courseCode.trim();
        String normalizedCourseCode = courseIdentifier.toUpperCase(Locale.ROOT);
        return records.findByIdAndOwnerId(studentId, studentId)
                .map(record -> {
                    var enrollment = record.courses().stream()
                            .filter(course -> course.status() == EnrollmentStatus.ENROLLED)
                            .filter(course -> course.courseCode().equals(normalizedCourseCode)
                                    || course.courseName().equalsIgnoreCase(courseIdentifier))
                            .findFirst();
                    if (enrollment.isEmpty()) {
                        return "해당 수강 과목을 찾을 수 없습니다.";
                    }

                    var request = requests.create(studentId, enrollment.get().courseCode(), reason.trim());
                    return "수강철회를 접수했습니다(신청번호 %s, 상태 %s). "
                            .formatted(request.no(), request.status())
                            + "즉시 처리되지 않으며 지도교수·학사팀 승인 대기 중입니다.";
                })
                .orElse("해당 학번의 학적 정보를 찾을 수 없습니다.");
    }

    private String studentId(ToolContext context) {
        Object value = context.getContext().get("studentId");
        if (!(value instanceof String studentId) || studentId.isBlank()) {
            return null;
        }
        return studentId;
    }

    private void markToolUsed(ToolContext context) {
        Object value = context.getContext().get("toolUsed");
        if (value instanceof AtomicBoolean toolUsed) {
            toolUsed.set(true);
        }
    }
}
