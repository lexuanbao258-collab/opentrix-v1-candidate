// SPDX-FileCopyrightText: 2026 TRICORE contributors
// SPDX-License-Identifier: Apache-2.0
package com.tricore.dxos.platform.manifest;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SafePatternsTest {
    @Test
    void acceptsBoundedFlatPatterns() {
        for (String ok : new String[]{"^[A-Z][A-Z0-9_]{1,31}$", "^\\d{4}-\\d{2}$", "[a-z]{3}", "^.{0,50}$", "^[^ ]{1,20}$"}) {
            assertThat(SafePatterns.violation(ok)).as(ok).isEmpty();
        }
    }

    @Test
    void rejectsGroupsAlternationUnboundedAndBackReferences() {
        for (String bad : new String[]{"(a+)+$", "^(a|aa)+$", "(a|b)", ".*", "a+", "a*b", "a{1,}", "a{1,1000}", "(?=a)", "\\1",
                "a??", "a{2}{3}", "[a-z]+", "\\p{L}", "[", "^[a-z]{1,10}[a-z]{1,10}[a-z]{1,10}[a-z]{1,10}$", "", "é"}) {
            assertThat(SafePatterns.violation(bad)).as(bad).isPresent();
        }
    }

    @Test
    void worstCaseAcceptedPatternFinishesQuickly() {
        assertThat(SafePatterns.violation("[a-z]{0,256}[a-z]{0,256}")).isPresent();
        String worst = "[a-z]{0,15}[a-z]{0,15}Z";
        assertThat(SafePatterns.violation(worst)).isEmpty();
        String input = "a".repeat(1000) + "!";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> Pattern.compile(worst).matcher(input).find());
    }
}
