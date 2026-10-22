package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.UserAccount;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from UserAccount u where u.id = :id")
    Optional<UserAccount> findLockedById(@Param("id") long id);
    Optional<UserAccount> findByUsername(String username);
    Optional<UserAccount> findByUsernameAndPasswordHash(String username, String passwordHash);
}
