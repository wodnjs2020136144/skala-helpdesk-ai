package com.skala.helpdesk.domain;

import java.util.List;

/**
 * 학적 스냅샷 — {@code totalCredits}는 이번 학기 이전까지 이수 완료한 누적 학점이고,
 * {@code courses}는 이번 학기 수강 중인 과목이다(합산하지 않는다 — 도구는 둘을 각각
 * 반환하고, 졸업 가능 여부 판단은 모델이 졸업요건 문서와 대조해서 내린다).
 */
public record StudentRecord(String studentId, String name, List<Enrollment> courses,
                             double gpa, boolean englishRequirementMet,
                             boolean capstoneCompleted, int totalCredits) {
}
