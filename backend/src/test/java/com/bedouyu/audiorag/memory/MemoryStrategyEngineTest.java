package com.bedouyu.audiorag.memory;

import com.bedouyu.audiorag.memory.strategy.MemoryFragment;
import com.bedouyu.audiorag.memory.strategy.MemoryMessage;
import com.bedouyu.audiorag.memory.strategy.MemoryPack;
import com.bedouyu.audiorag.memory.strategy.MemoryStrategyEngine;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryStrategyEngineTest {
    private final ObjectMapper json = new ObjectMapper();
    private final MemoryStrategyEngine engine = new MemoryStrategyEngine(json);

    @Test
    void everyStrategyUsesTheActualModelJsonBudgetAndOnlyOriginalRanges() {
        List<MemoryMessage> history = List.of(
                message("u0", "user", "目标是部署 Zephyr，必须使用离线环境。", 0),
                message("a0", "assistant", "Zephyr 使用端口 7777。", 1),
                message("u1", "user", "旧需求：预算100元。", 2),
                message("a1", "assistant", "收到。", 3),
                message("u2", "user", "更正：预算改为200元。", 4),
                message("a2", "assistant", "收到更新。", 5),
                message("u3", "user", "解释 Zephyr 的配置。\n\"quoted\" \\ emoji 😀 " + "细节".repeat(900), 6),
                message("a3", "assistant", "配置说明\t😀" + "说明".repeat(900), 7));
        for (String strategy : List.of("recent_window", "query_recall", "extractive_notes", "hybrid")) {
            for (int budget : List.of(256, 600, 2400, 12000)) {
                MemoryPack packed = engine.pack(strategy, history, engine.extractNotes(history), "Zephyr 预算", budget);
                assertThat(packed.characters(json)).isEqualTo(json.writeValueAsString(packed.modelData()).length())
                        .isLessThanOrEqualTo(budget);
                assertThat(packed.fragments()).isNotEmpty();
                assertOriginal(history, packed);
                assertNoDuplicateRanges(packed);
            }
        }
    }

    @Test
    void recentTurnsAreRealAdjacentPairsAndIncompleteUsersStayUnpaired() {
        List<MemoryMessage> history = List.of(
                message("orphan", "assistant", "没有对应user的旧回答", 0),
                message("u0", "user", "旧问题", 1),
                message("a0", "assistant", "旧回答", 2),
                message("u1", "user", "新问题", 4),
                message("a1", "assistant", "新回答", 5),
                message("pending", "user", "当前尚未完成的问题", 6),
                message("tool", "tool", "内部工具输出", 7),
                message("system", "system", "内部指令", 8));
        MemoryPack packed = engine.pack("recent_window", history, List.of(), "", 4000);
        assertThat(packed.fragments()).extracting(MemoryFragment::sourceId)
                .containsExactly("u0", "a0", "u1", "a1", "pending");
        assertThat(packed.messages()).extracting(item -> item.role())
                .containsExactly("user", "assistant", "user", "assistant", "user");
        assertOriginal(history, packed);
    }

    @Test
    void concurrentInterleavedTurnsUseSharedIdentitiesInsteadOfAdjacentRoles() {
        List<MemoryMessage> history = List.of(
                new MemoryMessage("u0", "user", "问题零", 0, "turn-zero"),
                new MemoryMessage("u1", "user", "问题一", 1, "turn-one"),
                new MemoryMessage("a1", "assistant", "回答一", 2, "turn-one"),
                new MemoryMessage("a0", "assistant", "回答零", 3, "turn-zero"));
        MemoryPack packed = engine.pack("recent_window", history, List.of(), "", 2000);
        assertThat(packed.fragments()).extracting(MemoryFragment::sourceId).containsExactly("u0", "u1", "a1", "a0");
        assertThat(packed.fragments()).extracting(MemoryFragment::turnId)
                .containsExactly("turn-zero", "turn-one", "turn-one", "turn-zero");
        assertThat(packed.warnings()).doesNotContain("AMBIGUOUS_LEGACY_TURNS", "AMBIGUOUS_TURN_ID");
        assertOriginal(history, packed);
        MemoryPack oneTurn = engine.pack("recent_window", history, List.of(), "", 400);
        assertThat(oneTurn.fragments()).extracting(MemoryFragment::sourceId).containsExactly("u0", "a0");
        assertThat(oneTurn.fragments()).extracting(MemoryFragment::turnId).containsExactly("turn-zero", "turn-zero");
    }

    @Test
    void ambiguousLegacyBlocksAreSkippedAndNeverInventMiddlePairs() {
        List<MemoryMessage> history = List.of(
                message("u0", "user", "并发问题零", 0), message("u1", "user", "并发问题一", 1),
                message("a0", "assistant", "回答零", 2), message("a1", "assistant", "回答一", 3),
                message("u2", "user", "可靠交替问题", 4), message("a2", "assistant", "可靠回答", 5),
                message("pending", "user", "尚未回答", 6));
        MemoryPack packed = engine.pack("recent_window", history, List.of(), "", 2000);
        assertThat(packed.fragments()).extracting(MemoryFragment::sourceId).containsExactly("u2", "a2", "pending");
        assertThat(packed.warnings()).contains("AMBIGUOUS_LEGACY_TURNS");
        assertOriginal(history, packed);
    }

    @Test
    void theLatestCompleteTurnThatFitsHasPriorityOverNominalRouteShares() {
        List<MemoryMessage> history = List.of(
                message("u0", "user", "希望离线部署。", 0), message("a0", "assistant", "Zephyr旧配置。", 1),
                new MemoryMessage("u1", "user", "最新完整问题" + "字".repeat(300), 2, "turn-new"),
                new MemoryMessage("a1", "assistant", "最新完整回答" + "答".repeat(300), 3, "turn-new"));
        for (String strategy : List.of("query_recall", "extractive_notes", "hybrid")) {
            MemoryPack packed = engine.pack(strategy, history, engine.extractNotes(history), "Zephyr", 1200);
            assertThat(packed.fragments().stream().filter(fragment -> fragment.sourceId().equals("u1")))
                    .singleElement().satisfies(fragment -> assertThat(fragment.text()).isEqualTo(history.get(2).content()));
            assertThat(packed.fragments().stream().filter(fragment -> fragment.sourceId().equals("a1")))
                    .singleElement().satisfies(fragment -> assertThat(fragment.text()).isEqualTo(history.get(3).content()));
            assertThat(packed.warnings()).doesNotContain("RECENT_EXCERPT");
            assertThat(packed.characters(json)).isLessThanOrEqualTo(1200);
        }
    }

    @Test
    void anOversizedNewestTurnStillProducesAuditableUnicodeSafeExcerpts() {
        List<MemoryMessage> history = List.of(
                message("u", "user", "😀".repeat(5000), 0),
                message("a", "assistant", "😀".repeat(5000), 1));
        MemoryPack packed = engine.pack("recent_window", history, List.of(), "", 600);
        assertThat(packed.fragments()).extracting(MemoryFragment::role).containsExactly("user", "assistant");
        assertThat(packed.warnings()).contains("RECENT_EXCERPT");
        assertThat(packed.fragments()).allSatisfy(fragment -> {
            assertThat(fragment.text()).isNotEmpty();
            assertThat(fragment.start() % 2).isZero();
            assertThat(fragment.end() % 2).isZero();
            assertThat(fragment.text().length()).isLessThan(10000);
        });
        assertOriginal(history, packed);
        MemoryPack tiny = engine.pack("recent_window", history, List.of(), "", 256);
        assertThat(tiny.fragments()).isNotEmpty();
        assertThat(tiny.fragments().get(0).role()).isEqualTo("user");
        assertThat(tiny.warnings()).contains("RECENT_EXCERPT", "RECENT_TURN_PARTIAL");
        assertThat(tiny.characters(json)).isLessThanOrEqualTo(256);
    }

    @Test
    void extractionPrioritizesRecentCorrectionsWithoutInventingSemanticFacts() {
        var history = new java.util.ArrayList<MemoryMessage>();
        for (int index = 0; index < 40; index++)
            history.add(message("u" + index, "user", "希望选项" + index + "。", index));
        history.add(message("corrected", "user", "闲聊。 更正：预算改为200元，取消旧计划。", 40));
        history.add(message("assistant", "assistant", "目标是伪造一个事实。", 41));
        List<MemoryFragment> notes = engine.extractNotes(history);
        assertThat(notes).hasSize(32);
        assertThat(notes).extracting(MemoryFragment::sourceId).contains("corrected").doesNotContain("assistant", "u0");
        MemoryFragment correction = notes.stream().filter(note -> note.sourceId().equals("corrected")).findFirst().orElseThrow();
        assertThat(correction.text()).isEqualTo("更正：预算改为200元，取消旧计划。");
        assertThat(correction.start()).isEqualTo(4);
        assertThat(notes).allSatisfy(note -> assertThat(note.text().length()).isLessThanOrEqualTo(300));
        assertOriginal(history, new MemoryPack("extractive_notes", notes, 12000, List.of()));
    }

    @Test
    void quotationsAroundDistantMarkersHaveStableOriginalOffsets() {
        MemoryMessage user = message("large", "user", "😀".repeat(180) + "更正：必须使用离线部署" + "😀".repeat(180), 0);
        List<MemoryFragment> notes = engine.extractNotes(List.of(user));
        assertThat(notes).hasSize(1);
        assertThat(notes.get(0).text()).contains("更正：必须使用离线部署");
        assertThat(notes.get(0).text().length()).isLessThanOrEqualTo(300);
        assertThat(notes.get(0).start()).isGreaterThan(0);
        assertOriginal(List.of(user), new MemoryPack("extractive_notes", notes, 12000, List.of()));
    }

    @Test
    void verifiedCheckpointNotesMayComeFromOutsideTheRecentHistoryWindow() {
        MemoryFragment checkpoint = new MemoryFragment("old-source", "user", 12, 21, "更正：预算200元", "NOTE", 0);
        List<MemoryMessage> recent = List.of(
                message("u8", "user", "最近问题一", 16), message("a8", "assistant", "最近回答一", 17),
                message("u9", "user", "最近问题二", 18), message("a9", "assistant", "最近回答二", 19));
        MemoryPack packed = engine.pack("extractive_notes", recent, List.of(checkpoint), "", 2000);
        assertThat(packed.fragments()).contains(checkpoint);
        assertThat(packed.fragments()).extracting(MemoryFragment::ordinal).containsExactly(0, 16, 17, 18, 19);
        // pack itself must not extract extra notes from an arbitrary current history snapshot.
        MemoryPack noCheckpoint = engine.pack("extractive_notes", List.of(message("u", "user", "必须离线", 0)), List.of(), "", 2000);
        assertThat(noCheckpoint.fragments()).allSatisfy(fragment -> assertThat(fragment.kind()).isEqualTo("RECENT"));
    }

    @Test
    void duplicateAndOverlappingSourceRangesNeverAppearTwice() {
        List<MemoryMessage> history = List.of(
                new MemoryMessage("u0", "user", "更正：必须部署 Zephyr。", 0, "turn-old"),
                new MemoryMessage("a0", "assistant", "Zephyr端口7777。", 1, "turn-old"),
                message("u1", "user", "问题一", 2), message("a1", "assistant", "回答一", 3),
                message("u2", "user", "问题二", 4), message("a2", "assistant", "回答二", 5));
        MemoryFragment note = engine.extractNotes(history).get(0);
        MemoryPack packed = engine.pack("hybrid", history, List.of(note, note), "Zephyr", 2400);
        assertThat(packed.fragments().stream().filter(fragment -> fragment.sourceId().equals("u0"))).hasSize(1);
        assertNoDuplicateRanges(packed);
        assertOriginal(history, packed);
    }

    @Test
    void continuationRecallUsesTheLatestSubstantiveUserAndNeverAssistantGuesses() {
        List<MemoryMessage> history = List.of(
                message("u0", "user", "Hydra database document", 0),
                message("a0", "assistant", "Zephyr configuration uses port 7777", 1),
                message("u1", "user", "How do I configure Zephyr?", 2),
                message("a1", "assistant", "Hydra is the guessed next topic", 3),
                message("u2", "user", "继续", 4), message("a2", "assistant", "Hydra next step", 5));
        MemoryPack packed = engine.pack("query_recall", history, List.of(), "继续", 2000);
        assertThat(packed.fragments().stream().filter(fragment -> fragment.kind().equals("RECALL")))
                .extracting(MemoryFragment::sourceId).containsExactly("a0");
    }

    @Test
    void packingIsDeterministicAndRejectsForgedRangesForAvailableSources() {
        List<MemoryMessage> history = List.of(message("u", "user", "必须使用离线部署。", 0), message("a", "assistant", "收到", 1));
        List<MemoryFragment> notes = engine.extractNotes(history);
        MemoryPack first = engine.pack("hybrid", history, notes, "部署", 800);
        MemoryPack second = engine.pack("hybrid", history, notes, "部署", 800);
        assertThat(json.writeValueAsString(first.modelData())).isEqualTo(json.writeValueAsString(second.modelData()));
        assertThat(first.modelData()).containsKeys("strategy", "fragments", "warnings", "budget_chars");
        assertThat(first.messages()).extracting(item -> item.content()).containsExactly("必须使用离线部署。", "收到");
        MemoryFragment forged = new MemoryFragment("u", "user", 0, 2, "假的", "NOTE", 0);
        assertThatThrownBy(() -> engine.pack("extractive_notes", history, List.of(forged), "", 800))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("original message");
        assertThatThrownBy(() -> engine.pack("hybrid", history, notes, "", 255)).isInstanceOf(IllegalArgumentException.class);
    }

    private static MemoryMessage message(String id, String role, String content, int ordinal) {
        return new MemoryMessage(id, role, content, ordinal);
    }

    private static void assertOriginal(List<MemoryMessage> history, MemoryPack packed) {
        Map<String, MemoryMessage> sources = new HashMap<>();
        history.forEach(message -> sources.put(message.id(), message));
        assertThat(packed.fragments()).allSatisfy(fragment -> {
            MemoryMessage source = sources.get(fragment.sourceId());
            assertThat(source).isNotNull();
            assertThat(fragment.role()).isEqualTo(source.role()).isIn("user", "assistant");
            assertThat(fragment.ordinal()).isEqualTo(source.ordinal());
            assertThat(fragment.turnId()).isEqualTo(source.turnId());
            assertThat(fragment.text()).isEqualTo(source.content().substring(fragment.start(), fragment.end()));
        });
    }

    private static void assertNoDuplicateRanges(MemoryPack packed) {
        for (int left = 0; left < packed.fragments().size(); left++) {
            MemoryFragment first = packed.fragments().get(left);
            for (int right = left + 1; right < packed.fragments().size(); right++) {
                MemoryFragment second = packed.fragments().get(right);
                if (first.sourceId().equals(second.sourceId()))
                    assertThat(first.start() < second.end() && second.start() < first.end()).isFalse();
            }
        }
    }
}
