package com.system.resume.repository;

import com.system.resume.model.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, Long> {

    Optional<Candidate> findByEmail(String email);

    boolean existsByEmail(String email);

    List<Candidate> findByLastNameContainingIgnoreCaseOrFirstNameContainingIgnoreCase(String lastName, String firstName);
}
