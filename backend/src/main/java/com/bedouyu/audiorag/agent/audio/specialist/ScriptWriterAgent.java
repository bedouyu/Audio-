package com.bedouyu.audiorag.agent.audio.specialist;
import com.bedouyu.audiorag.agent.*;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
public class ScriptWriterAgent implements SpecialistAgent {
    public AgentProfile profile() {
        return new AgentProfile("script_writer", "音频编写", "script-writer",
                Set.of("read_artifact", "estimate_duration"), "SCRIPT_DRAFT", 6);
    }
}
