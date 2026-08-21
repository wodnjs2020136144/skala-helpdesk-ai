package com.skala.helpdesk.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import com.skala.helpdesk.repository.StudentRecordRepository;
import com.skala.helpdesk.repository.WithdrawalRequestRepository;

class ToolSchemaContractTest {

    @Test
    void 모델에는_학번과_승인_기능을_노출하지_않는다() {
        var records = new StudentRecordRepository();
        var provider = MethodToolCallbackProvider.builder()
                .toolObjects(
                        new AcademicTools(records),
                        new RequestTools(records, new WithdrawalRequestRepository()))
                .build();

        Map<String, ToolDefinition> definitions = Arrays.stream(provider.getToolCallbacks())
                .map(callback -> callback.getToolDefinition())
                .collect(Collectors.toMap(ToolDefinition::name, Function.identity()));

        assertThat(definitions).containsOnlyKeys("myCourses", "gradStatus", "requestDrop");
        assertThat(definitions.values()).allSatisfy(definition -> {
            assertThat(definition.description()).doesNotContain("TODO");
            assertThat(definition.inputSchema()).doesNotContain("studentId", "approve");
        });
        assertThat(definitions.get("myCourses").inputSchema()).doesNotContain("courseCode", "reason");
        assertThat(definitions.get("gradStatus").inputSchema()).doesNotContain("courseCode", "reason");
        assertThat(definitions.get("requestDrop").inputSchema())
                .contains("courseCode", "reason")
                .doesNotContain("status");
    }
}
