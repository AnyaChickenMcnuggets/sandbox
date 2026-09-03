package com.rpatest.orchestrator.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrchestratorNamesTest {

    @Test
    void keepsLettersDigitsAndUnderscore() {
        assertThat(OrchestratorNames.sanitize("Job_1")).isEqualTo("Job_1");
    }

    @Test
    void replacesSpacesAndSpecialCharactersWithUnderscore() {
        assertThat(OrchestratorNames.sanitize("Test Job #12.3")).isEqualTo("Test_Job__12_3");
    }

    @Test
    void replacesNonLatinLetters() {
        assertThat(OrchestratorNames.sanitize("Тест")).isEqualTo("____");
    }

    @Test
    void returnsEmptyStringForNull() {
        assertThat(OrchestratorNames.sanitize(null)).isEmpty();
    }
}
