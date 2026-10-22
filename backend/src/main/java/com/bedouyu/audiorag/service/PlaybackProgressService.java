package com.bedouyu.audiorag.service;

import com.bedouyu.audiorag.common.ApiException;
import com.bedouyu.audiorag.model.entity.PlaybackProgress;
import com.bedouyu.audiorag.repository.PlaybackProgressRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional
public class PlaybackProgressService {
    private final ContinuousAudioStore queues;
    private final PlaybackProgressRepository progress;
    public PlaybackProgressService(ContinuousAudioStore queues, PlaybackProgressRepository progress) {
        this.queues = queues; this.progress = progress;
    }
    public Map<String, Object> get(long userId, String queueId) {
        queues.status(userId, queueId); // The queue ownership check always precedes access to progress.
        return progress.findById(queueId).map(this::data).orElseGet(() -> Map.of(
                "queue_id", queueId, "segment_index", 0, "position_seconds", 0.0,
                "playback_rate", 1.0, "completed", false, "updated_at", ""));
    }
    public Map<String, Object> save(long userId, String queueId, int index, double seconds, double rate, boolean completed) {
        if (index < 0 || !Double.isFinite(seconds) || seconds < 0 || seconds > 86400
                || !Double.isFinite(rate) || rate < 0.5 || rate > 3)
            throw new ApiException(HttpStatus.BAD_REQUEST, "收听位置或播放速度无效");
        var queue = queues.status(userId, queueId);
        int total = ((Number) queue.get("total_segments")).intValue();
        var states = (List<?>) queue.get("segments");
        if (index >= total || states == null || states.stream().noneMatch(s -> s instanceof Map<?, ?> m
                && ((Number) m.get("segment_number")).intValue() == index + 1 && "READY".equals(m.get("status"))))
            throw new ApiException(HttpStatus.CONFLICT, "该音频片段尚不可播放");
        if (completed && (index != total - 1 || !"COMPLETE".equals(queue.get("status"))))
            throw new ApiException(HttpStatus.CONFLICT, "只有最后一个音频片段可以记为听完");
        var entry = progress.findById(queueId).orElseGet(() -> new PlaybackProgress(queueId, userId,
                ((Number) queue.get("project_id")).longValue()));
        entry.update(index, seconds, rate, completed);
        return data(progress.save(entry));
    }
    public boolean completed(long userId, String queueId) {
        queues.commandForUser(userId, queueId);
        return progress.findById(queueId).map(PlaybackProgress::isCompleted).orElse(false);
    }
    private Map<String, Object> data(PlaybackProgress value) {
        return Map.of("queue_id", value.getQueueId(), "segment_index", value.getSegmentIndex(),
                "position_seconds", value.getPositionSeconds(), "playback_rate", value.getPlaybackRate(),
                "completed", value.isCompleted(), "updated_at", value.getUpdatedAt().toString());
    }
}
