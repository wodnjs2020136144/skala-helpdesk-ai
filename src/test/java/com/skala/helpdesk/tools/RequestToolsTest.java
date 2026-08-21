package com.skala.helpdesk.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import com.skala.helpdesk.domain.RequestStatus;
import com.skala.helpdesk.repository.StudentRecordRepository;
import com.skala.helpdesk.repository.WithdrawalRequestRepository;

class RequestToolsTest {

    private final WithdrawalRequestRepository requests = new WithdrawalRequestRepository();
    private final RequestTools tools = new RequestTools(new StudentRecordRepository(), requests);

    @Test
    void 수강철회는_승인_대기로만_접수한다() {
        String result = tools.requestDrop("CS201", "개인 일정 변경", context("2021001"));

        assertThat(result)
                .contains("신청번호 WD-0001")
                .contains("상태 PENDING")
                .contains("즉시 처리되지 않")
                .contains("승인 대기");
        assertThat(requests.pending()).singleElement().satisfies(request -> {
            assertThat(request.studentId()).isEqualTo("2021001");
            assertThat(request.courseCode()).isEqualTo("CS201");
            assertThat(request.status()).isEqualTo(RequestStatus.PENDING);
        });
    }

    @Test
    void 다른_학생의_수강_과목은_철회_접수하지_않는다() {
        String result = tools.requestDrop("CS201", "요청", context("2021002"));

        assertThat(result).isEqualTo("해당 수강 과목을 찾을 수 없습니다.");
        assertThat(requests.pending()).isEmpty();
    }

    @Test
    void 과목명으로도_철회_접수를_생성한다() {
        String result = tools.requestDrop("알고리즘", "개인 일정 변경", context("2021001"));

        assertThat(result).contains("신청번호 WD-0001").contains("상태 PENDING");
        assertThat(requests.pending()).singleElement()
                .satisfies(request -> assertThat(request.courseCode()).isEqualTo("CS201"));
    }

    @Test
    void 수강하지_않는_과목은_철회_접수하지_않는다() {
        String result = tools.requestDrop("CS999", "요청", context("2021001"));

        assertThat(result).isEqualTo("해당 수강 과목을 찾을 수 없습니다.");
        assertThat(requests.pending()).isEmpty();
    }

    @Test
    void 철회_사유가_없으면_접수하지_않는다() {
        String result = tools.requestDrop("CS201", "  ", context("2021001"));

        assertThat(result).isEqualTo("수강철회 사유를 입력해 주세요.");
        assertThat(requests.pending()).isEmpty();
    }

    @Test
    void 인증_정보가_없으면_접수하지_않는다() {
        String result = tools.requestDrop("CS201", "요청", new ToolContext(Map.of()));

        assertThat(result).isEqualTo("해당 학번의 학적 정보를 찾을 수 없습니다.");
        assertThat(requests.pending()).isEmpty();
    }

    @Test
    void 승인_메서드는_모델_도구로_노출하지_않는다() {
        Method[] toolMethods = Arrays.stream(RequestTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .toArray(Method[]::new);

        assertThat(toolMethods).extracting(Method::getName).containsExactly("requestDrop");
        assertThat(Arrays.stream(RequestTools.class.getDeclaredMethods()).map(Method::getName))
                .doesNotContain("approve");
    }

    @Test
    void 철회_접수를_실행하면_도구_사용_여부를_표시한다() {
        AtomicBoolean toolUsed = new AtomicBoolean(false);

        tools.requestDrop("CS201", "요청", new ToolContext(Map.of(
                "studentId", "2021001",
                "toolUsed", toolUsed)));

        assertThat(toolUsed).isTrue();
    }

    private ToolContext context(String studentId) {
        return new ToolContext(Map.of("studentId", studentId));
    }
}
