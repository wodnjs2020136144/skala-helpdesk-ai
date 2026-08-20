package com.skala.helpdesk.domain;

/** 수강철회 신청 1건 — 접수(PENDING)와 승인(APPROVED)을 분리한다. */
public record WithdrawalRequest(String no, String studentId, String courseCode, String reason,
                                 RequestStatus status) {

    public WithdrawalRequest approve() {
        return new WithdrawalRequest(no, studentId, courseCode, reason, RequestStatus.APPROVED);
    }
}
