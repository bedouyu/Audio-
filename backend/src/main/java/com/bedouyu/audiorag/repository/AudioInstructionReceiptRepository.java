package com.bedouyu.audiorag.repository;
import com.bedouyu.audiorag.model.entity.AudioInstructionReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
public interface AudioInstructionReceiptRepository extends JpaRepository<AudioInstructionReceipt, Long> {
    Optional<AudioInstructionReceipt> findByUserIdAndRequestId(long userId, String requestId);
    @Transactional void deleteByUserIdAndProjectId(long userId, long projectId);
}
