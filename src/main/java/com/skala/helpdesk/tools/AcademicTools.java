package com.skala.helpdesk.tools;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import com.skala.helpdesk.domain.EnrollmentStatus;
import com.skala.helpdesk.repository.StudentRecordRepository;

/**
 * 담당: B(첫 번째 책임자) · 리뷰: A — Phase 4 (교안 p.317).
 *
 * <p>모델은 코드를 보지 않는다. {@code description}만 본다. 학번은 파라미터가 아니라
 * {@link ToolContext}로 받는다(모델이 바꿔 부를 수 없는 통로 — {@code HelpDeskService}가
 * 채워 넣는다). 소유자 검증은 이미 완성된
 * {@link StudentRecordRepository#findByIdAndOwnerId}가 강제한다 — 이 메서드만 쓰면 된다.
 *
 * <p>참고: {@code day3-consult-agent/tools/OrderTools.java},
 * {@code SpringAI_실습/ch09_tools/OrderTools.java}
 *
 * <p>완료 기준(검증 시나리오 ②⑤, docs/검증-시나리오.md):
 * <ul>
 *   <li>"제가 지금 몇 학점이죠?" 같은 질문에 도구가 불린다 — description에 "언제 쓰는지"와
 *       예시 표현을 넣는다</li>
 *   <li>다른 학번의 수강내역·졸업요건 충족도는 조회되지 않는다("찾을 수 없습니다"로 응답 —
 *       존재하지 않는 학번과 남의 학번을 구분해 알려주면 그 자체가 정보 노출이다, p.318)</li>
 * </ul>
 */
@Component
public class AcademicTools {

    private final StudentRecordRepository records;

    public AcademicTools(StudentRecordRepository records) {
        this.records = records;
    }

    @Tool(description = "인증된 학생 본인의 현재 수강 과목과 누적 이수 학점을 조회한다. "
            + "사용자가 '내 수강과목', '지금 몇 학점', '이번 학기 뭐 듣고 있어'처럼 "
            + "본인의 수강·학점 현황을 물으면 사용한다.")
    public String myCourses(ToolContext context) {
        markToolUsed(context);
        String studentId = studentId(context);
        if (studentId == null) {
            return "해당 학번의 학적 정보를 찾을 수 없습니다.";
        }

        return records.findByIdAndOwnerId(studentId, studentId)
                .map(r -> {
                    String courses = String.join(", ", r.courses().stream()
                            .filter(course -> course.status() == EnrollmentStatus.ENROLLED)
                            .map(course -> "%s %s(%d학점)".formatted(
                                    course.courseCode(), course.courseName(), course.credits()))
                            .toList());
                    String currentCourses = courses.isEmpty() ? "없음" : courses;
                    return "현재 수강 과목: %s. 누적 이수 학점: %d학점."
                            .formatted(currentCourses, r.totalCredits());
                })
                .orElse("해당 학번의 학적 정보를 찾을 수 없습니다.");
    }

    @Tool(description = "인증된 학생 본인의 졸업요건 충족 현황을 조회한다. 사용자가 '나 졸업 가능해', "
            + "'졸업요건 얼마나 충족했어'처럼 물으면 누적 학점·GPA·어학·캡스톤 현황을 확인할 때 사용한다. "
            + "최종 졸업 가능 여부는 학사 규정 검색 결과와 함께 판단해야 한다.")
    public String gradStatus(ToolContext context) {
        markToolUsed(context);
        String studentId = studentId(context);
        if (studentId == null) {
            return "해당 학번의 학적 정보를 찾을 수 없습니다.";
        }

        return records.findByIdAndOwnerId(studentId, studentId)
                .map(r -> "졸업요건 현황: 누적 학점 %d학점, GPA %.1f, 어학요건 %s, 캡스톤 %s."
                        .formatted(r.totalCredits(), r.gpa(),
                                r.englishRequirementMet() ? "충족" : "미충족",
                                r.capstoneCompleted() ? "이수" : "미이수"))
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
