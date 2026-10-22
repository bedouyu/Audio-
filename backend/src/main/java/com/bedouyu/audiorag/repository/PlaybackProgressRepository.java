package com.bedouyu.audiorag.repository;
import com.bedouyu.audiorag.model.entity.PlaybackProgress;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PlaybackProgressRepository extends JpaRepository<PlaybackProgress, String> {
    @org.springframework.transaction.annotation.Transactional
    void deleteByUserIdAndProjectId(long userId, long projectId);
}
