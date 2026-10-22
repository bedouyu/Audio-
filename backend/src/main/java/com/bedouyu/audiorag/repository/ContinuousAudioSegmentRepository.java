package com.bedouyu.audiorag.repository;
import com.bedouyu.audiorag.model.entity.ContinuousAudioSegment;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ContinuousAudioSegmentRepository extends JpaRepository<ContinuousAudioSegment, Long> {
    List<ContinuousAudioSegment> findByQueueIdOrderBySegmentNumberAsc(String queueId);
    Optional<ContinuousAudioSegment> findByQueueIdAndSegmentNumber(String queueId, int segmentNumber);
    long countByQueueId(String queueId);
}
