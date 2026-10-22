package com.bedouyu.audiorag.agent;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.stream.Collectors;

@Component
public class AgentRegistry {
    private final Map<String, AgentProfile> profiles;
    public AgentRegistry(List<SpecialistAgent> agents) {
        profiles = agents.stream().map(SpecialistAgent::profile)
                .collect(Collectors.toUnmodifiableMap(AgentProfile::id, p -> p));
    }
    public AgentProfile require(String id) {
        AgentProfile profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("Agent 未注册：" + id);
        return profile;
    }
    public List<AgentProfile> profiles() { return profiles.values().stream().sorted(Comparator.comparing(AgentProfile::id)).toList(); }
}
