package com.skala.helpdesk.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Repository;

import com.skala.helpdesk.domain.RequestStatus;
import com.skala.helpdesk.domain.WithdrawalRequest;

/**
 * 수강철회 신청 저장소. 완성 상태로 제공된다 — 손대지 않는다.
 *
 * <p>접수({@link #create})와 승인({@link #approve})을 분리하는 것이 이 실습의 핵심이다
 * (교안 학사운영규정 제3조 "지도교수 승인 후 처리"). {@code create}는 도구(모델이 닿을
 * 수 있는 경로, {@code RequestTools})에서 부르고, {@code approve}는
 * {@code AdminController}(모델이 닿을 수 없는 경로)에서만 부른다.
 */
@Repository
public class WithdrawalRequestRepository {

    private final List<WithdrawalRequest> requests = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger(1);

    public synchronized WithdrawalRequest create(String studentId, String courseCode, String reason) {
        WithdrawalRequest request = new WithdrawalRequest("WD-%04d".formatted(seq.getAndIncrement()),
                studentId, courseCode, reason, RequestStatus.PENDING);
        requests.add(request);
        return request;
    }

    public synchronized List<WithdrawalRequest> pending() {
        return requests.stream().filter(r -> r.status() == RequestStatus.PENDING).toList();
    }

    public synchronized Optional<WithdrawalRequest> approve(String no) {
        for (int i = 0; i < requests.size(); i++) {
            WithdrawalRequest r = requests.get(i);
            if (r.no().equals(no)) {
                WithdrawalRequest approved = r.approve();
                requests.set(i, approved);
                return Optional.of(approved);
            }
        }
        return Optional.empty();
    }
}
