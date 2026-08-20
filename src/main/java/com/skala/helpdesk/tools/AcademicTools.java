package com.skala.helpdesk.tools;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

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

    // TODO(B, Phase 4): description을 채운다. "내 수강과목", "지금 몇 학점" 같은 표현을
    // 예시로 넣어야 모델이 언제 이 도구를 쓸지 판단할 수 있다(p.318 도구 설계 리뷰 표).
    @Tool(description = "TODO(B, Phase 4): 수강 과목·이수 학점을 조회한다는 것을 명시한다.")
    public String myCourses(@ToolParam(description = "TODO(B): 학번 파라미터 설명") String studentId,
                            ToolContext context) {
        String ownerId = (String) context.getContext().get("studentId");
        return records.findByIdAndOwnerId(studentId, ownerId)
                .map(r -> "TODO(B, Phase 4): %s의 수강 과목·누적 학점(%d학점)을 정리해 응답한다."
                        .formatted(r.name(), r.totalCredits()))
                .orElse("해당 학번의 학적 정보를 찾을 수 없습니다.");
    }

    // TODO(B, Phase 4): 졸업요건 충족도. RAG가 규정(130학점 등, graduation-requirements.md)을
    // 답하고 이 도구가 현재 누적 학점·GPA·어학·캡스톤 이수 여부를 반환하면, 모델이 둘을
    // 조합해 "졸업 가능한지"를 판단한다(검증 시나리오 ③ 멀티턴, p.319).
    @Tool(description = "TODO(B, Phase 4): 졸업요건 충족도(누적학점·GPA·어학·캡스톤)를 조회한다는 것을 명시한다.")
    public String gradStatus(@ToolParam(description = "TODO(B): 학번 파라미터 설명") String studentId,
                             ToolContext context) {
        String ownerId = (String) context.getContext().get("studentId");
        return records.findByIdAndOwnerId(studentId, ownerId)
                .map(r -> "TODO(B, Phase 4): 누적학점=%d, GPA=%.1f, 어학요건=%s, 캡스톤=%s 를 정리해 응답한다."
                        .formatted(r.totalCredits(), r.gpa(),
                                r.englishRequirementMet() ? "충족" : "미충족",
                                r.capstoneCompleted() ? "이수" : "미이수"))
                .orElse("해당 학번의 학적 정보를 찾을 수 없습니다.");
    }
}
