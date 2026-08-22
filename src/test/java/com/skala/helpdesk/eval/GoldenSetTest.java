package com.skala.helpdesk.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import com.skala.helpdesk.eval.GoldenSet.Category;
import com.skala.helpdesk.eval.GoldenSet.SourcePolicy;

class GoldenSetTest {

    private final GoldenSet goldenSet = GoldenSet.academicHelpDesk();

    @Test
    void containsExactlyTwentyUniqueCasesAcrossAllCategories() {
        assertThat(goldenSet.cases()).hasSize(20);
        assertThat(goldenSet.cases()).extracting(GoldenSet.Case::id).doesNotHaveDuplicates();
        assertThat(goldenSet.cases()).extracting(GoldenSet.Case::category)
                .contains(Category.values());
    }

    @Test
    void expectedSourceFilesExistInTheRagCorpus() {
        Path corpus = Path.of("src/main/resources/helpdesk-docs");

        assertThat(goldenSet.cases())
                .filteredOn(c -> c.sourcePolicy() == SourcePolicy.REQUIRED)
                .allSatisfy(c -> assertThat(
                        Files.isRegularFile(corpus.resolve(c.expectedSource()))
                                || Files.isRegularFile(corpus.resolve("regulations").resolve(c.expectedSource())))
                        .as("%s의 기대 출처 %s", c.id(), c.expectedSource())
                        .isTrue());
    }

    @Test
    void sourcePoliciesCoverRequiredToolOnlyAndNoEvidenceCases() {
        assertThat(goldenSet.cases()).filteredOn(c -> c.sourcePolicy() == SourcePolicy.REQUIRED)
                .hasSize(15);
        assertThat(goldenSet.cases()).filteredOn(c -> c.sourcePolicy() == SourcePolicy.IGNORE)
                .hasSize(2);
        assertThat(goldenSet.cases()).filteredOn(c -> c.sourcePolicy() == SourcePolicy.EMPTY)
                .hasSize(3)
                .allSatisfy(c -> assertThat(c.category()).isEqualTo(Category.NO_EVIDENCE));
        assertThat(goldenSet.cases()).filteredOn(GoldenSet.Case::expectedToolUsed).hasSize(3);
    }
}
