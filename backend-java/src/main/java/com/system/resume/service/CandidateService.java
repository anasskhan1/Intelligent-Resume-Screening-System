package com.system.resume.service;

import com.system.resume.dto.CandidateRequestDto;
import com.system.resume.dto.CandidateResponseDto;
import com.system.resume.model.Candidate;
import com.system.resume.model.Resume;
import com.system.resume.repository.CandidateRepository;
import com.system.resume.repository.ResumeRepository;
import com.system.resume.security.SecurityAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Service managing candidate profiles, unique email constraints,
 * search queries, safe candidate deletion with resume file cleanup,
 * and transactional DTO projection.
 */
@Service
@Transactional(readOnly = true)
public class CandidateService {

    private static final Logger logger = LoggerFactory.getLogger(CandidateService.class);

    private final CandidateRepository candidateRepository;
    private final ResumeRepository resumeRepository;
    private final FileStorageService fileStorageService;
    private final SecurityAuthorizationService securityAuthorizationService;

    @Autowired
    public CandidateService(
            CandidateRepository candidateRepository,
            ResumeRepository resumeRepository,
            FileStorageService fileStorageService,
            SecurityAuthorizationService securityAuthorizationService
    ) {
        this.candidateRepository = candidateRepository;
        this.resumeRepository = resumeRepository;
        this.fileStorageService = fileStorageService;
        this.securityAuthorizationService = securityAuthorizationService;
    }

    public CandidateService(
            CandidateRepository candidateRepository,
            ResumeRepository resumeRepository,
            FileStorageService fileStorageService
    ) {
        this(candidateRepository, resumeRepository, fileStorageService, null);
    }

    /**
     * Creates a new candidate profile, enforcing email uniqueness.
     *
     * @param candidate the candidate entity to persist
     * @return the saved candidate
     */
    @Transactional
    public Candidate createCandidate(Candidate candidate) {
        if (candidate == null) {
            throw new IllegalArgumentException("Candidate cannot be null.");
        }
        if (securityAuthorizationService != null && securityAuthorizationService.isHiringManager()) {
            throw new AccessDeniedException("Access denied: Hiring managers cannot create, update, or delete candidates");
        }
        if (candidate.getEmail() == null || candidate.getEmail().isBlank()) {
            throw new IllegalArgumentException("Candidate email is required.");
        }

        if (candidateRepository.existsByEmail(candidate.getEmail().trim().toLowerCase())) {
            throw new IllegalArgumentException("A candidate with email '" + candidate.getEmail() + "' already exists.");
        }

        Candidate saved = candidateRepository.save(candidate);
        logger.info("Created candidate profile with ID: {}", saved.getId());
        return saved;
    }

    /**
     * DTO-facing method for creating a candidate with in-transaction mapping.
     */
    @Transactional
    public CandidateResponseDto createCandidateDto(CandidateRequestDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("CandidateRequestDto cannot be null.");
        }
        Candidate candidate = new Candidate(
                dto.firstName() != null ? dto.firstName().trim() : "",
                dto.lastName() != null ? dto.lastName().trim() : "",
                dto.email() != null ? dto.email().trim().toLowerCase() : "",
                dto.phone() != null ? dto.phone().trim() : null
        );
        Candidate saved = createCandidate(candidate);
        return CandidateResponseDto.fromEntity(saved);
    }

    /**
     * Retrieves an existing candidate by email or creates a new one if not found.
     * If an existing candidate has missing contact details (e.g. null phone) or a placeholder
     * filename-derived name, safely enriches them without overwriting established information.
     */
    @Transactional
    public Candidate getOrCreateCandidate(String email, String firstName, String lastName, String phone) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Candidate email cannot be blank.");
        }

        final String safeEmail = email.trim().toLowerCase();
        final String safeFirst = (firstName != null && !firstName.isBlank())
                ? (firstName.trim().length() > 100 ? firstName.trim().substring(0, 100) : firstName.trim())
                : "Candidate";
        final String safeLast = (lastName != null && !lastName.isBlank())
                ? (lastName.trim().length() > 100 ? lastName.trim().substring(0, 100) : lastName.trim())
                : "Profile";
        final String safePhone = (phone != null && !phone.isBlank())
                ? (phone.trim().length() > 50 ? phone.trim().substring(0, 50) : phone.trim())
                : null;

        return candidateRepository.findByEmail(safeEmail)
                .map(existing -> {
                    boolean updated = false;

                    // 1. Enrich missing phone if newly extracted phone is valid
                    if ((existing.getPhone() == null || existing.getPhone().isBlank()) && safePhone != null) {
                        existing.setPhone(safePhone);
                        updated = true;
                    }

                    // 2. Enrich placeholder filename-derived name ("Candidate Profile") with genuine extracted name
                    if ("Candidate".equalsIgnoreCase(existing.getFirstName()) && "Profile".equalsIgnoreCase(existing.getLastName())
                            && !"Candidate".equalsIgnoreCase(safeFirst)) {
                        existing.setFirstName(safeFirst);
                        existing.setLastName(safeLast);
                        updated = true;
                    }

                    if (updated) {
                        Candidate saved = candidateRepository.save(existing);
                        logger.info("Enriched existing candidate ID: {} with extracted contact information.", saved.getId());
                        return saved;
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    Candidate newCandidate = new Candidate(
                            safeFirst,
                            safeLast,
                            safeEmail,
                            safePhone
                    );
                    Candidate saved = candidateRepository.save(newCandidate);
                    logger.info("Created new candidate during getOrCreate with ID: {} ('{} {}', email: '{}')",
                            saved.getId(), saved.getFirstName(), saved.getLastName(), saved.getEmail());
                    return saved;
                });
    }

    /**
     * Retrieves candidate entity by primary key.
     */
    public Candidate getCandidateById(Long id) {
        Candidate candidate = candidateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Candidate not found with ID: " + id));
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewCandidate(candidate);
        }
        return candidate;
    }

    /**
     * DTO-facing method for retrieving candidate by ID.
     */
    public CandidateResponseDto getCandidateDtoById(Long id) {
        return CandidateResponseDto.fromEntity(getCandidateById(id));
    }

    /**
     * Finds candidate entity by email address.
     */
    public Optional<Candidate> getCandidateByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        Optional<Candidate> opt = candidateRepository.findByEmail(email.trim().toLowerCase());
        if (opt.isPresent() && securityAuthorizationService != null) {
            securityAuthorizationService.validateCanViewCandidate(opt.get());
        }
        return opt;
    }

    /**
     * DTO-facing method for finding candidate by email address.
     */
    public Optional<CandidateResponseDto> getCandidateDtoByEmail(String email) {
        return getCandidateByEmail(email).map(CandidateResponseDto::fromEntity);
    }

    /**
     * Searches candidates by partial first name or last name match.
     */
    public List<Candidate> searchCandidates(String query) {
        List<Candidate> candidates;
        if (query == null || query.isBlank()) {
            candidates = candidateRepository.findAll();
        } else {
            String trimmed = query.trim();
            candidates = candidateRepository.findByLastNameContainingIgnoreCaseOrFirstNameContainingIgnoreCase(trimmed, trimmed);
        }
        return filterPermittedCandidates(candidates);
    }

    /**
     * DTO-facing method for searching candidates.
     */
    public List<CandidateResponseDto> searchCandidateDtos(String query) {
        return searchCandidates(query).stream()
                .map(CandidateResponseDto::fromEntity)
                .toList();
    }

    /**
     * Returns all candidate entities permitted for current user.
     */
    public List<Candidate> getAllCandidates() {
        return filterPermittedCandidates(candidateRepository.findAll());
    }

    /**
     * Filters candidate list according to role-based ownership and assignment permissions.
     */
    private List<Candidate> filterPermittedCandidates(List<Candidate> candidates) {
        if (securityAuthorizationService == null || securityAuthorizationService.isAdmin()) {
            return candidates;
        }

        if (securityAuthorizationService.isHiringManager()) {
            List<Long> assignedJobIds = securityAuthorizationService.getAssignedJobIdsForCurrentHiringManager();
            if (assignedJobIds.isEmpty()) {
                return Collections.emptyList();
            }
        }

        return candidates.stream()
                .filter(c -> {
                    try {
                        securityAuthorizationService.validateCanViewCandidate(c);
                        return true;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
    }

    /**
     * DTO-facing method for retrieving all candidates.
     */
    public List<CandidateResponseDto> getAllCandidateDtos() {
        return getAllCandidates().stream()
                .map(CandidateResponseDto::fromEntity)
                .toList();
    }

    /**
     * Updates an existing candidate profile entity.
     */
    @Transactional
    public Candidate updateCandidate(Long id, Candidate details) {
        Candidate existing = getCandidateById(id);
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageCandidate(existing);
        }

        if (details.getEmail() != null && !details.getEmail().isBlank()) {
            String newEmail = details.getEmail().trim().toLowerCase();
            if (!newEmail.equalsIgnoreCase(existing.getEmail()) && candidateRepository.existsByEmail(newEmail)) {
                throw new IllegalArgumentException("Email '" + newEmail + "' is already in use by another candidate.");
            }
            existing.setEmail(newEmail);
        }

        if (details.getFirstName() != null) {
            existing.setFirstName(details.getFirstName().trim());
        }
        if (details.getLastName() != null) {
            existing.setLastName(details.getLastName().trim());
        }
        if (details.getPhone() != null) {
            existing.setPhone(details.getPhone().trim());
        }

        Candidate updated = candidateRepository.save(existing);
        logger.info("Updated candidate profile ID: {}", updated.getId());
        return updated;
    }

    /**
     * DTO-facing method for updating candidate.
     */
    @Transactional
    public CandidateResponseDto updateCandidateDto(Long id, CandidateRequestDto dto) {
        if (dto == null) {
            throw new IllegalArgumentException("CandidateRequestDto cannot be null.");
        }
        Candidate details = new Candidate(
                dto.firstName(),
                dto.lastName(),
                dto.email(),
                dto.phone()
        );
        Candidate updated = updateCandidate(id, details);
        return CandidateResponseDto.fromEntity(updated);
    }

    /**
     * Safely deletes a candidate profile and cleans up physical resume files:
     * 1. Extracts physical file paths for candidate's resumes in the read context.
     * 2. Commits the database deletion (cascades to resumes and skills in MySQL).
     * 3. Performs post-commit physical file deletion, safely handling partial cleanup errors.
     *
     * @param id candidate ID
     */
    @Transactional
    public void deleteCandidate(Long id) {
        Candidate existing = getCandidateById(id);
        if (securityAuthorizationService != null) {
            securityAuthorizationService.validateCanManageCandidate(existing);
        }

        // 1. Collect all physical resume file paths before database removal
        List<Resume> candidateResumes = resumeRepository.findByCandidateId(id);
        List<String> filePathsToDelete = candidateResumes.stream()
                .map(Resume::getFilePath)
                .filter(path -> path != null && !path.startsWith("memory://"))
                .toList();

        // 2. Execute database deletion and flush immediately to guarantee DB consistency
        candidateRepository.deleteById(id);
        candidateRepository.flush();
        logger.info("Deleted candidate ID: {} from database.", id);

        // 3. Post-commit physical file cleanup; individual failures are logged as warnings without corrupting DB state
        for (String filePath : filePathsToDelete) {
            try {
                fileStorageService.deleteFile(filePath);
            } catch (Exception ex) {
                logger.warn("Could not delete physical resume file '{}' during candidate deletion: {}", filePath, ex.getMessage());
            }
        }
    }
}
