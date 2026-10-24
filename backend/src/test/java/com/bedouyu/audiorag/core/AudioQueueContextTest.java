package com.bedouyu.audiorag.core;

import com.bedouyu.audiorag.core.loop.*;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class AudioQueueContextTest {
    @Test void delegatesShareTheRootSelectedAudioQueueAndSeeLaterRootUpdates() {
        var root = new AgentExecutionContext("run", "session", "supervisor", 1, 2,
                new AgentBudget(), new EvidenceLedger(), null, null, ignored -> { });
        root.audioQueueId = "first";
        var child = root.child("script_writer", Set.of());
        assertThat(child.audioQueueId).isEqualTo("first");
        assertThat(child.rootContext()).isSameAs(root);
        root.audioQueueId = "selected-later";
        var grandchild = child.child("review_agent", Set.of());
        assertThat(grandchild.rootContext()).isSameAs(root);
        assertThat(grandchild.audioQueueId).isEqualTo("selected-later");
    }
}
