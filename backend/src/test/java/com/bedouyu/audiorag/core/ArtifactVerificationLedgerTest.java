package com.bedouyu.audiorag.core;

import com.bedouyu.audiorag.core.guard.ArtifactVerificationLedger;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ArtifactVerificationLedgerTest {
    @Test void fullReadRequiresEveryCharacterWithoutGaps() {
        var ledger = new ArtifactVerificationLedger();
        ledger.read("a", "raw", "rendered", 0, 4, 10);
        ledger.read("a", "raw", "rendered", 6, 10, 10);
        assertThat(ledger.fullRead("a", "raw", "rendered")).isFalse();
        ledger.read("a", "raw", "rendered", 3, 7, 10);
        assertThat(ledger.fullRead("a", "raw", "rendered")).isTrue();
        assertThat(ledger.result("a").readSpans()).containsExactly(new ArtifactVerificationLedger.ReadSpan(0, 10));
        assertThat(ledger.fullRead("b", "raw", "rendered")).isFalse();
    }

    @Test void changingEitherHashInvalidatesPriorReadAndCheck() {
        var ledger = new ArtifactVerificationLedger();
        ledger.read("a", "raw", "rendered", 0, 10, 10);
        ledger.check("a", "raw", "rendered", true, List.of());
        ledger.read("a", "raw", "new-rendered", 0, 3, 11);
        assertThat(ledger.result("a").checked()).isFalse();
        assertThat(ledger.result("a").readFull()).isFalse();
        ledger.check("a", "new-raw", "new-rendered", false, List.of("真实问题"));
        assertThat(ledger.result("a").readSpans()).isEmpty();
        assertThat(ledger.result("a").localCheckPassed()).isFalse();
        assertThat(ledger.result("a").issues()).containsExactly("真实问题");
    }

    @Test void checkDoesNotCountAsReadingAndResultsCannotBeMutated() {
        var ledger = new ArtifactVerificationLedger();
        ledger.check("a", "raw", "rendered", true, List.of());
        assertThat(ledger.result("a").readFull()).isFalse();
        assertThatThrownBy(() -> ledger.result("a").readSpans().add(new ArtifactVerificationLedger.ReadSpan(0, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ledger.read("a", "raw", "rendered", -1, 1, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
