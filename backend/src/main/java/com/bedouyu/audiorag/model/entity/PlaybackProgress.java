package com.bedouyu.audiorag.model.entity;

import jakarta.persistence.*;
import java.time.Instant;

/** Listening is user activity, independent of generation and learning-plan completion. */
@Entity
@Table(name = "audio_playback_progress", indexes = @Index(columnList = "user_id,project_id"))
public class PlaybackProgress {
    @Id @Column(name = "queue_id", length = 36) private String queueId;
    @Column(name = "user_id", nullable = false) private long userId;
    @Column(name = "project_id", nullable = false) private long projectId;
    @Column(name = "segment_index", nullable = false) private int segmentIndex;
    @Column(name = "position_seconds", nullable = false) private double positionSeconds;
    @Column(name = "playback_rate", nullable = false) private double playbackRate = 1;
    @Column(nullable = false) private boolean completed;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected PlaybackProgress() { }
    public PlaybackProgress(String queueId, long userId, long projectId) {
        this.queueId = queueId; this.userId = userId; this.projectId = projectId; updatedAt = Instant.now();
    }
    public void update(int index, double position, double rate, boolean finished) {
        segmentIndex = index; positionSeconds = position; playbackRate = rate; completed = finished; updatedAt = Instant.now();
    }
    public String getQueueId() { return queueId; }
    public long getUserId() { return userId; }
    public int getSegmentIndex() { return segmentIndex; }
    public double getPositionSeconds() { return positionSeconds; }
    public double getPlaybackRate() { return playbackRate; }
    public boolean isCompleted() { return completed; }
    public Instant getUpdatedAt() { return updatedAt; }
}
