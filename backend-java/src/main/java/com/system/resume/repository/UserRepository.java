package com.system.resume.repository;

import com.system.resume.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<User> findByEmailAndIsActiveTrue(String email);

    @Query("SELECT u FROM User u WHERE u.email = :username")
    Optional<User> findByUsername(@Param("username") String username);
}

