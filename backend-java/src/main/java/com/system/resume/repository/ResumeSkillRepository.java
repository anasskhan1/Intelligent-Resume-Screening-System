package com.system.resume.repository;

import com.system.resume.model.ResumeSkill;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ResumeSkillRepository extends JpaRepository<ResumeSkill, Long> {

    List<ResumeSkill> findByResumeId(Long resumeId);

    List<ResumeSkill> findBySkillNameIgnoreCase(String skillName);

    List<ResumeSkill> findByResumeIdAndCategoryIgnoreCase(Long resumeId, String category);

    void deleteByResumeId(Long resumeId);
}
