package com.skala.helpdesk.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.skala.helpdesk.eval.GoldenSet.Case;
import com.skala.helpdesk.eval.GoldenSetEvaluationTest.Evaluation;
import com.skala.helpdesk.eval.GoldenSetEvaluationTest.Metrics;

class GoldenSetMetricsTest {

    @Test
    void twentyCasesProduceNearestRankP95AndPerfectRates() {
        List<Evaluation> results = passingResults();

        Metrics metrics = Metrics.from(results);

        assertThat(metrics.passRate()).isEqualTo(1.0);
        assertThat(metrics.sourceHitRate()).isEqualTo(1.0);
        assertThat(metrics.toolSuccessRate()).isEqualTo(1.0);
        assertThat(metrics.toolErrorRate()).isZero();
        assertThat(metrics.noEvidencePassRate()).isEqualTo(1.0);
        assertThat(metrics.p95Millis()).isEqualTo(19);
        assertThat(metrics.errorCount()).isZero();
    }

    @Test
    void aToolQualityFailureIsIncludedInTheToolErrorRateWithoutAnException() {
        List<Evaluation> results = passingResults();
        int toolIndex = indexOf(results, "TL-01");
        Evaluation original = results.get(toolIndex);
        results.set(toolIndex, new Evaluation(original.testCase(), original.durationMs(),
                true, true, false, List.of(), ""));

        Metrics metrics = Metrics.from(results);

        assertThat(metrics.toolSuccessRate()).isCloseTo(2.0 / 3.0, within(1.0e-12));
        assertThat(metrics.toolErrorRate()).isCloseTo(1.0 / 3.0, within(1.0e-12));
        assertThat(metrics.errorCount()).isZero();
        assertThat(metrics.passRate()).isEqualTo(19.0 / 20.0);
    }

    @Test
    void sourceAndNoEvidenceRatesUseOnlyTheirApplicableCases() {
        List<Evaluation> results = passingResults();
        replaceSourceResult(results, "AC-01", false, true);
        replaceSourceResult(results, "NE-01", true, false);

        Metrics metrics = Metrics.from(results);

        assertThat(metrics.sourceHitRate()).isEqualTo(14.0 / 15.0);
        assertThat(metrics.noEvidencePassRate()).isEqualTo(2.0 / 3.0);
        assertThat(metrics.passRate()).isEqualTo(18.0 / 20.0);
    }

    private static List<Evaluation> passingResults() {
        List<Evaluation> results = new ArrayList<>();
        List<Case> cases = GoldenSet.academicHelpDesk().cases();
        for (int index = 0; index < cases.size(); index++) {
            results.add(new Evaluation(cases.get(index), index + 1, true, true, true,
                    List.of(), ""));
        }
        return results;
    }

    private static int indexOf(List<Evaluation> results, String id) {
        for (int index = 0; index < results.size(); index++) {
            if (results.get(index).testCase().id().equals(id)) {
                return index;
            }
        }
        throw new IllegalArgumentException("Golden Set id를 찾을 수 없습니다: " + id);
    }

    private static void replaceSourceResult(List<Evaluation> results, String id,
                                            boolean sourcePassed, boolean keywordsPassed) {
        int index = indexOf(results, id);
        Evaluation original = results.get(index);
        results.set(index, new Evaluation(original.testCase(), original.durationMs(), sourcePassed,
                keywordsPassed, true, List.of(), ""));
    }
}
