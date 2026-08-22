package com.skala.helpdesk.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * 담당: B(첫 번째 책임자) · 리뷰: A — Phase 8 품질 기준선.
 *
 * <p>모델 답변 전체 문장을 고정하지 않고 핵심 사실, 기대 출처, Tool 사용 여부를 검증한다.
 * 프롬프트·모델·검색 설정을 바꿀 때 실제 모델 평가를 다시 실행해 회귀를 찾는다.
 */
public record GoldenSet(List<Case> cases) {

    public GoldenSet {
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
        if (cases.size() != 20) {
            throw new IllegalArgumentException("Golden Set은 정확히 20개여야 합니다.");
        }
        if (new HashSet<>(cases.stream().map(Case::id).toList()).size() != cases.size()) {
            throw new IllegalArgumentException("Golden Set id는 중복될 수 없습니다.");
        }
    }

    public enum Category {
        ACADEMIC_REGULATION, GRADUATION, SCHOLARSHIP, TOOL, NO_EVIDENCE
    }

    /** 출처가 필수인지, 없어야 하는지, Tool 전용 질문이라 검사하지 않을지를 나타낸다. */
    public enum SourcePolicy {
        REQUIRED, EMPTY, IGNORE
    }

    public record Case(
            String id,
            Category category,
            String question,
            String studentId,
            SourcePolicy sourcePolicy,
            String expectedSource,
            List<String> expectedKeywords,
            boolean expectedToolUsed) {

        public Case {
            id = requireText(id, "id");
            category = Objects.requireNonNull(category, "category");
            question = requireText(question, "question");
            studentId = requireText(studentId, "studentId");
            sourcePolicy = Objects.requireNonNull(sourcePolicy, "sourcePolicy");
            expectedSource = expectedSource == null ? "" : expectedSource.strip();
            expectedKeywords = List.copyOf(Objects.requireNonNull(expectedKeywords, "expectedKeywords"));
            if (expectedKeywords.isEmpty() || expectedKeywords.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("expectedKeywords에는 한 개 이상의 값이 필요합니다.");
            }
            if (sourcePolicy == SourcePolicy.REQUIRED && expectedSource.isBlank()) {
                throw new IllegalArgumentException("REQUIRED 문항에는 expectedSource가 필요합니다.");
            }
            if (sourcePolicy != SourcePolicy.REQUIRED && !expectedSource.isBlank()) {
                throw new IllegalArgumentException("REQUIRED가 아닌 문항의 expectedSource는 비워야 합니다.");
            }
        }

        private static String requireText(String value, String name) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + "은(는) 비어 있을 수 없습니다.");
            }
            return value.strip();
        }
    }

    /** 학사 안내 챗봇의 Phase 8 기준선 20문항. */
    public static GoldenSet academicHelpDesk() {
        String student = "2021001";
        String noEvidence = "정확한 규정을 확인할 수 없습니다";
        return new GoldenSet(List.of(
                required("AC-01", Category.ACADEMIC_REGULATION, "한 학기에 최대 몇 학점까지 신청할 수 있나요?",
                        student, "학사운영에관한규칙.pdf", "21학점", "24학점", "3.5"),
                required("AC-02", Category.ACADEMIC_REGULATION, "수강정정 기간은 언제까지인가요?",
                        student, "academic-regulations.md", "개강", "1주"),
                required("AC-03", Category.ACADEMIC_REGULATION, "수강철회하면 성적표와 GPA에는 어떻게 반영되나요?",
                        student, "academic-regulations.md", "W", "포함되지"),
                required("AC-04", Category.ACADEMIC_REGULATION, "한 학기에 수강철회할 수 있는 과목 수를 알려주세요.",
                        student, "academic-regulations.md", "2과목"),
                required("AC-05", Category.ACADEMIC_REGULATION,
                        "성적 발표 후 이의신청 기간과 신청일로부터 처리 결과 통보 기간을 알려주세요.",
                        student, "academic-regulations.md", "1주", "5일"),

                required("GR-01", Category.GRADUATION, "졸업하려면 총 몇 학점을 이수해야 하나요?",
                        student, "graduation-requirements.md", "130학점"),
                required("GR-02", Category.GRADUATION, "졸업에 필요한 전공필수와 전공선택 학점 기준을 알려주세요.",
                        student, "graduation-requirements.md", "45학점", "15학점", "60학점"),
                required("GR-03", Category.GRADUATION, "졸업 어학 요건의 TOEIC 기준은 몇 점인가요?",
                        student, "graduation-requirements.md", "700점"),
                required("GR-04", Category.GRADUATION, "졸업논문이나 캡스톤 프로젝트는 몇 학점인가요?",
                        student, "graduation-requirements.md", "3학점"),
                required("GR-05", Category.GRADUATION, "졸업 사정을 받기 위한 전체 조건을 알려주세요.",
                        student, "graduation-requirements.md", "130학점", "2.0", "어학", "졸업논문"),

                required("SC-01", Category.SCHOLARSHIP, "직전 학기 GPA가 4.0이면 성적 장학금은 얼마인가요?",
                        student, "scholarship-policy.md", "전액"),
                required("SC-02", Category.SCHOLARSHIP, "직전 학기 GPA가 3.8이면 성적 장학금 비율은 얼마인가요?",
                        student, "scholarship-policy.md", "50%"),
                required("SC-03", Category.SCHOLARSHIP, "직전 학기에 11학점만 이수해도 성적 장학금을 받을 수 있나요?",
                        student, "scholarship-policy.md", "12학점", "제외"),
                required("SC-04", Category.SCHOLARSHIP, "성적 장학금은 별도로 신청해야 하나요?",
                        student, "scholarship-policy.md", "자동", "필요가 없습니다"),

                tool("TL-01", "제가 현재 듣는 과목과 누적 학점을 알려주세요.", student,
                        "100학점", "CS201", "CS301"),
                requiredTool("TL-02", "제 현재 상태로 졸업 가능한지 부족한 조건까지 확인해주세요.", student,
                        "graduation-requirements.md", "100학점", "130학점", "캡스톤"),
                tool("TL-03", "알고리즘 과목을 개인 사정으로 수강철회 신청해주세요.", student,
                        "신청번호", "PENDING", "승인"),

                noEvidence("NE-01", "오늘 학생식당 점심 메뉴가 무엇인가요?", student, noEvidence),
                noEvidence("NE-02", "기숙사 통금 시간이 몇 시인가요?", student, noEvidence),
                noEvidence("NE-03", "교내 주차 정기권 요금은 얼마인가요?", student, noEvidence)
        ));
    }

    private static Case required(String id, Category category, String question, String studentId,
                                 String source, String... keywords) {
        return new Case(id, category, question, studentId, SourcePolicy.REQUIRED, source,
                List.of(keywords), false);
    }

    private static Case requiredTool(String id, String question, String studentId, String source,
                                     String... keywords) {
        return new Case(id, Category.TOOL, question, studentId, SourcePolicy.REQUIRED, source,
                List.of(keywords), true);
    }

    private static Case tool(String id, String question, String studentId, String... keywords) {
        return new Case(id, Category.TOOL, question, studentId, SourcePolicy.IGNORE, "",
                List.of(keywords), true);
    }

    private static Case noEvidence(String id, String question, String studentId, String keyword) {
        return new Case(id, Category.NO_EVIDENCE, question, studentId, SourcePolicy.EMPTY, "",
                List.of(keyword), false);
    }
}
