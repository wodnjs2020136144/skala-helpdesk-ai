package com.skala.helpdesk.domain;

/** 현재 학기 수강 과목 1건. */
public record Enrollment(String studentId, String courseCode, String courseName,
                          int credits, EnrollmentStatus status) {
}
