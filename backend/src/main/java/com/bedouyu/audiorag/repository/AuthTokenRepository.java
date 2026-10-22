package com.bedouyu.audiorag.repository;

import com.bedouyu.audiorag.model.entity.AuthToken;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, String> {

}
