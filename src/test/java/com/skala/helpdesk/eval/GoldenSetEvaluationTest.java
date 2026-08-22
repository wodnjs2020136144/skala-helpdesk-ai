package com.skala.helpdesk.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.skala.helpdesk.chat.AnswerDto;
import com.skala.helpdesk.chat.HelpDeskService;
import com.skala.helpdesk.eval.GoldenSet.Case;
import com.skala.helpdesk.eval.GoldenSet.SourcePolicy;

/**
 * 실제 모델·RAG·Tool을 함께 호출하는 Phase 8 평가 실행기.
 *
 * <p>{@code ./gradlew goldenSetTest}로만 실행되며 일반 {@code test}/{@code build}에서는
 * 제외된다. 답변 원문은 개인정보·모델 출력 노출을 피하려고 보고서에 저장하지 않는다.
 */
@Tag("live-ai")
@SpringBootTest
class GoldenSetEvaluationTest {

    private static final Path REPORT = Path.of("build/reports/golden-set/results.md");

    @Autowired
    private HelpDeskService helpDeskService;

    @Test
    void evaluatesTwentyCasesAndWritesQualityReport() throws IOException {
        List<Evaluation> results = new ArrayList<>();

        for (Case testCase : GoldenSet.academicHelpDesk().cases()) {
            long started = System.nanoTime();
            try {
                AnswerDto answer = helpDeskService.ask(testCase.question(), testCase.studentId(),
                        "golden-" + testCase.id().toLowerCase(Locale.ROOT));
                long durationMs = elapsedMillis(started);
                boolean sourcePassed = sourcePassed(testCase, answer);
                boolean keywordsPassed = keywordsPassed(testCase, answer.answer());
                boolean toolPassed = answer.toolUsed() == testCase.expectedToolUsed();
                List<String> sources = answer.sources().stream()
                        .map(AnswerDto.Source::document).distinct().sorted().toList();
                results.add(new Evaluation(testCase, durationMs, sourcePassed, keywordsPassed,
                        toolPassed, sources, ""));
            }
            catch (Exception exception) {
                results.add(new Evaluation(testCase, elapsedMillis(started), false, false, false,
                        List.of(), exception.getClass().getSimpleName()));
            }
        }

        Metrics metrics = Metrics.from(results);
        writeReport(results, metrics);

        assertThat(results).hasSize(20);
        assertThat(metrics.errorCount()).as("모델 호출 오류 수 (보고서: %s)", REPORT).isZero();
        assertThat(metrics.passRate()).as("전체 통과율 (보고서: %s)", REPORT)
                .isGreaterThanOrEqualTo(minRate("GOLDEN_SET_MIN_PASS_RATE", 0.80));
        assertThat(metrics.sourceHitRate()).as("필수 출처 적중률")
                .isGreaterThanOrEqualTo(minRate("GOLDEN_SET_MIN_SOURCE_HIT_RATE", 0.80));
        assertThat(metrics.toolSuccessRate()).as("Tool 문항 성공률")
                .isGreaterThanOrEqualTo(minRate("GOLDEN_SET_MIN_TOOL_SUCCESS_RATE", 0.80));
        assertThat(metrics.noEvidencePassRate()).as("근거 없음 문항 통과율")
                .isGreaterThanOrEqualTo(minRate("GOLDEN_SET_MIN_NO_EVIDENCE_RATE", 0.80));
        assertThat(metrics.p95Millis()).as("비스트리밍 P95 응답시간")
                .isLessThanOrEqualTo(maxP95Millis());
    }

    private static boolean sourcePassed(Case testCase, AnswerDto answer) {
        return switch (testCase.sourcePolicy()) {
            case REQUIRED -> answer.sources().stream()
                    .anyMatch(source -> testCase.expectedSource().equals(source.document()));
            case EMPTY -> answer.sources().isEmpty();
            case IGNORE -> true;
        };
    }

    private static boolean keywordsPassed(Case testCase, String answer) {
        String normalizedAnswer = normalize(answer);
        return testCase.expectedKeywords().stream()
                .map(GoldenSetEvaluationTest::normalize)
                .allMatch(normalizedAnswer::contains);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    private static double minRate(String name, double defaultValue) {
        String configured = System.getenv(name);
        if (configured == null || configured.isBlank()) {
            return defaultValue;
        }
        double value = Double.parseDouble(configured);
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + "은 0.0~1.0 범위여야 합니다.");
        }
        return value;
    }

    private static long maxP95Millis() {
        String configured = System.getenv("GOLDEN_SET_MAX_P95_MS");
        return configured == null || configured.isBlank() ? 5_000 : Long.parseLong(configured);
    }

    private static void writeReport(List<Evaluation> results, Metrics metrics) throws IOException {
        Files.createDirectories(REPORT.getParent());
        StringBuilder report = new StringBuilder("""
                # Phase 8 Golden Set 평가 결과

                - 실행 시각(UTC): %s
                - 전체 문항: %d
                - 전체 통과율: %.1f%%
                - 필수 출처 적중률: %.1f%%
                - Tool 문항 성공률: %.1f%%
                - Tool 문항 오류율: %.1f%%
                - 근거 없음 통과율: %.1f%%
                - 비스트리밍 P95: %dms
                - 호출 오류: %d건

                | ID | 범주 | 시간(ms) | 출처 | 핵심 사실 | Tool | 종합 | 실제 sources | 오류 |
                |---|---|---:|:---:|:---:|:---:|:---:|---|---|
                """.formatted(Instant.now(), results.size(), metrics.passRate() * 100,
                metrics.sourceHitRate() * 100, metrics.toolSuccessRate() * 100,
                metrics.toolErrorRate() * 100, metrics.noEvidencePassRate() * 100,
                metrics.p95Millis(), metrics.errorCount()));

        for (Evaluation result : results) {
            report.append("| %s | %s | %d | %s | %s | %s | %s | %s | %s |%n".formatted(
                    result.testCase().id(), result.testCase().category(), result.durationMs(),
                    mark(result.sourcePassed()), mark(result.keywordsPassed()),
                    mark(result.toolPassed()), mark(result.passed()),
                    escape(String.join(", ", result.sources())), escape(result.error())));
        }
        report.append("\n> 답변 원문은 개인정보 및 모델 출력 노출을 피하기 위해 저장하지 않습니다.\n");
        Files.writeString(REPORT, report, StandardCharsets.UTF_8);
    }

    private static String mark(boolean passed) {
        return passed ? "PASS" : "FAIL";
    }

    private static String escape(String value) {
        return value.replace("|", "\\|").replace("\n", " ");
    }

    record Evaluation(Case testCase, long durationMs, boolean sourcePassed,
                      boolean keywordsPassed, boolean toolPassed, List<String> sources,
                      String error) {
        boolean passed() {
            return error.isEmpty() && sourcePassed && keywordsPassed && toolPassed;
        }
    }

    record Metrics(double passRate, double sourceHitRate, double toolSuccessRate,
                   double toolErrorRate, double noEvidencePassRate, long p95Millis,
                   long errorCount) {
        static Metrics from(List<Evaluation> results) {
            List<Evaluation> sourceCases = results.stream()
                    .filter(r -> r.testCase().sourcePolicy() == SourcePolicy.REQUIRED).toList();
            List<Evaluation> toolCases = results.stream()
                    .filter(r -> r.testCase().expectedToolUsed()).toList();
            List<Evaluation> noEvidenceCases = results.stream()
                    .filter(r -> r.testCase().sourcePolicy() == SourcePolicy.EMPTY).toList();
            List<Long> durations = results.stream().map(Evaluation::durationMs)
                    .sorted(Comparator.naturalOrder()).toList();
            int p95Index = Math.max(0, (int) Math.ceil(durations.size() * 0.95) - 1);

            double toolSuccessRate = rate(
                    toolCases.stream().filter(Evaluation::passed).count(), toolCases.size());
            return new Metrics(
                    rate(results.stream().filter(Evaluation::passed).count(), results.size()),
                    rate(sourceCases.stream().filter(Evaluation::sourcePassed).count(), sourceCases.size()),
                    toolSuccessRate,
                    1.0 - toolSuccessRate,
                    rate(noEvidenceCases.stream().filter(Evaluation::passed).count(), noEvidenceCases.size()),
                    durations.get(p95Index),
                    results.stream().filter(r -> !r.error().isEmpty()).count());
        }

        private static double rate(long count, long total) {
            return total == 0 ? 0.0 : (double) count / total;
        }
    }
}
