package com.skala.helpdesk.repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.skala.helpdesk.domain.Enrollment;
import com.skala.helpdesk.domain.EnrollmentStatus;
import com.skala.helpdesk.domain.StudentRecord;

/**
 * 데모용 인메모리 학적 저장소. 완성 상태로 제공된다 — 손대지 않는다.
 *
 * <p><b>소유자 조건이 쿼리 안에 있다.</b> {@link #findByIdAndOwnerId}는 존재하지 않는
 * 학번과 남의 학번을 구분하지 않고 똑같이 빈 값을 반환한다 — 그 자체가 정보 노출이기
 * 때문이다(교안 p.298·p.305). {@code AcademicTools}·{@code RequestTools}를 만들 때 이
 * 메서드만 쓰면 소유자 검증을 빠뜨릴 수 없다.
 *
 * <p>2021001은 졸업이 임박했지만 아직 학점·캡스톤이 부족한 케이스, 2021002는 저학년
 * 케이스로 만들어 뒀다 — 검증 시나리오 ①②③("졸업 학점 요건" → "제 학점은" → "졸업
 * 가능해요?")이 실제로 "아직 부족하다"는 유의미한 답으로 이어지도록 학점을 130학점(졸업
 * 요건, graduation-requirements.md)보다 일부러 적게 잡았다.
 */
@Repository
public class StudentRecordRepository {

    private static final Map<String, StudentRecord> RECORDS = Map.of(
            "2021001", new StudentRecord("2021001", "홍길동", List.of(
                    new Enrollment("2021001", "CS201", "알고리즘", 3, EnrollmentStatus.ENROLLED),
                    new Enrollment("2021001", "CS301", "데이터베이스", 3, EnrollmentStatus.ENROLLED),
                    new Enrollment("2021001", "GE201", "글쓰기와소통", 2, EnrollmentStatus.ENROLLED)
            ), 3.8, true, false, 100),
            "2021002", new StudentRecord("2021002", "김학사", List.of(
                    new Enrollment("2021002", "CS101", "자료구조", 3, EnrollmentStatus.ENROLLED)
            ), 3.2, false, false, 45));

    public Optional<StudentRecord> findByIdAndOwnerId(String studentId, String ownerId) {
        return Optional.ofNullable(RECORDS.get(studentId))
                .filter(r -> r.studentId().equals(ownerId));
    }
}
