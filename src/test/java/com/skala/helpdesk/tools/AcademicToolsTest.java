package com.skala.helpdesk.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import com.skala.helpdesk.repository.StudentRecordRepository;

class AcademicToolsTest {

    private final AcademicTools tools = new AcademicTools(new StudentRecordRepository());

    @Test
    void 본인_수강_과목과_누적_학점을_조회한다() {
        String result = tools.myCourses(context("2021001"));

        assertThat(result)
                .contains("CS201 알고리즘(3학점)")
                .contains("CS301 데이터베이스(3학점)")
                .contains("누적 이수 학점: 100학점");
    }

    @Test
    void 인증_학번이_다르면_다른_학생_정보를_반환하지_않는다() {
        String result = tools.myCourses(context("2021002"));

        assertThat(result)
                .contains("CS101 자료구조(3학점)")
                .contains("누적 이수 학점: 45학점")
                .doesNotContain("CS201", "100학점", "홍길동");
    }

    @Test
    void 없는_학번과_인증_정보_누락은_같은_문구로_응답한다() {
        String unknown = tools.myCourses(context("9999999"));
        String missing = tools.myCourses(new ToolContext(Map.of()));

        assertThat(unknown).isEqualTo(missing);
        assertThat(unknown).isEqualTo("해당 학번의 학적 정보를 찾을 수 없습니다.");
    }

    @Test
    void 졸업요건_현황을_모두_반환한다() {
        String result = tools.gradStatus(context("2021001"));

        assertThat(result)
                .contains("누적 학점 100학점")
                .contains("GPA 3.8")
                .contains("어학요건 충족")
                .contains("캡스톤 미이수");
    }

    @Test
    void 학번은_모델이_생성하는_도구_파라미터가_아니다() {
        Method[] toolMethods = Arrays.stream(AcademicTools.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(Tool.class))
                .toArray(Method[]::new);

        assertThat(toolMethods).hasSize(2);
        assertThat(toolMethods).allSatisfy(method ->
                assertThat(method.getParameterTypes()).containsExactly(ToolContext.class));
    }

    @Test
    void 학적_조회를_실행하면_도구_사용_여부를_표시한다() {
        AtomicBoolean toolUsed = new AtomicBoolean(false);

        tools.myCourses(new ToolContext(Map.of("studentId", "2021001", "toolUsed", toolUsed)));

        assertThat(toolUsed).isTrue();
    }

    private ToolContext context(String studentId) {
        return new ToolContext(Map.of("studentId", studentId));
    }
}
