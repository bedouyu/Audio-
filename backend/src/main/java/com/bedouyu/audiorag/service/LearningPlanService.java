package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.common.Hashing;
import com.bedouyu.audiorag.model.entity.LearningPoint;
import com.bedouyu.audiorag.repository.LearningPointRepository;
import com.bedouyu.audiorag.repository.LearningProjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

/** 锁定项目后预留知识点，防止两个课程重复领取同一项。 */
@Service
public class LearningPlanService {
    private static final List<String> FOCI = List.of("核心定义与边界", "关键组成与角色", "完整工作流程", "典型业务场景",
            "关键参数与设计取舍", "常见问题与排查", "实践案例拆解", "性能与成本优化", "安全与风险边界", "与相邻概念的区别",
            "面试表达与追问", "复盘清单与下一步");
    private final LearningPointRepository points;
    private final LearningProjectRepository projects;

    public LearningPlanService(LearningPointRepository points, LearningProjectRepository projects) {
        this.points = points; this.projects = projects;
    }
    @Transactional
    public Focus reserveNext(long projectId, String rootTopic) {
        projects.findLockedById(projectId).orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        if (points.countByProjectId(projectId) == 0) {
            for (String suffix : FOCI) {
                String focus = rootTopic + "：" + suffix;
                points.save(new LearningPoint(projectId, focus, Hashing.sha256(focus)));
            }
            points.flush();
        }
        LearningPoint point = points.findFirstByProjectIdAndStatusOrderByIdAsc(projectId, "PENDING")
                .orElseThrow(() -> new IllegalStateException("学习计划已全部覆盖，请补充新的主题或资料"));
        point.reserve();
        return new Focus(point.getId(), point.getFocus());
    }
    @Transactional
    public void complete(long pointId) { points.findById(pointId).ifPresent(LearningPoint::complete); }
    @Transactional
    public void release(long pointId) { points.findById(pointId).ifPresent(LearningPoint::release); }
    @Transactional
    public int appendConfirmedPlan(long projectId, List<String> titles) {
        projects.findLockedById(projectId).orElseThrow(() -> new IllegalArgumentException("项目不存在"));
        int added = 0;
        for (String title : titles) {
            String focus = title.strip();
            String hash = Hashing.sha256(focus);
            if (!points.existsByProjectIdAndFocusHash(projectId, hash)) {
                points.save(new LearningPoint(projectId, focus, hash)); added++;
            }
        }
        return added;
    }
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(long projectId) {
        return points.findByProjectIdOrderByIdAsc(projectId).stream().map(point -> Map.<String, Object>of(
                "id", point.getId(), "focus", point.getFocus(), "status", point.getStatus(),
                "created_at", point.getCreatedAt(), "completed_at",
                point.getCompletedAt() == null ? "" : point.getCompletedAt())).toList();
    }
    public record Focus(long id, String title) { }
}
