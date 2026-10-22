package com.bedouyu.audiorag.rag.experiment;

import com.bedouyu.audiorag.rag.strategy.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RagExperimentRunnerTest {
    @Test void documentHitDoesNotPretendAllAnswerEvidenceWasFound() {
        var chunk = new TextSegment("a", 0, 0, 5, "first", 5);
        var context = new EvidenceSegment("a", 0, 5, "first", 10, 1, List.of(chunk.id()));
        var result = RagExperimentRunner.metrics(List.of(new RankedChunk(chunk, .9, 0)), List.of(context),
                List.of(new RagExperimentRunner.Span("a", 0, 5), new RagExperimentRunner.Span("a", 10, 15)));
        assertThat(result).containsEntry("document_recall_at_5", 1.0).containsEntry("gold_span_coverage", .5)
                .containsEntry("all_evidence_covered", 0.0);
    }
    @Test void nestedGoldQuotesAreCountedOnce() {
        var context = new EvidenceSegment("a", 0, 10, "0123456789", 10, 1, List.of("a:0"));
        var result = RagExperimentRunner.metrics(List.of(), List.of(context),
                List.of(new RagExperimentRunner.Span("a", 0, 10), new RagExperimentRunner.Span("a", 2, 5)));
        assertThat(result).containsEntry("gold_span_coverage", 1.0).containsEntry("evidence_density", 1.0);
    }
}
