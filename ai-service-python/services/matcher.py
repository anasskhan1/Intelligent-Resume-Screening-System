"""
Resume Matching and Candidate Ranking Engine.
Evaluates candidate fit against job requirements using skill overlap,
TF-IDF semantic cosine similarity, and experience evaluation.
"""

import re
from typing import List, Optional, Set, Tuple

from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity

try:
    from models.schemas import (
        BatchRankRequest,
        BatchRankResponse,
        CandidateProfile,
        MatchRequest,
        MatchResponse,
        RankedCandidate,
    )
    from services.extractor import extract_skills
except ImportError:
    from ..models.schemas import (
        BatchRankRequest,
        BatchRankResponse,
        CandidateProfile,
        MatchRequest,
        MatchResponse,
        RankedCandidate,
    )
    from .extractor import extract_skills


# Tightly targeted patterns to match professional experience while excluding education lengths
EXPERIENCE_YEARS_PATTERNS = [
    # 1. Matches: "5 years of professional experience", "5 years experience", "5-year experience", "3+ yrs relevant experience"
    #    Requires the explicit presence of the 'experience' keyword; naturally rejects "4-year bachelor's degree"
    re.compile(
        r"\b(\d{1,2})\+?(?:\s*-\s*|\s+)(?:years?|yrs?)\b"
        r"(?:\s+(?:of|in|with)\s+|\s+)"
        r"(?:(?:professional|relevant|work|industry|hands-on|total)\s+)?experience\b",
        re.IGNORECASE
    ),
    # 2. Key-value style: "Experience: 5 years", "Work Experience - 8 yrs", "Total experience: 5+ years"
    re.compile(
        r"\b(?:total\s+)?(?:work\s+)?experience\s*[:=-]\s*(\d{1,2})\+?\s*(?:years?|yrs?)\b",
        re.IGNORECASE
    ),
    # 3. Leading quantifier: "Over 6 years of experience", "More than 4 years in software engineering"
    re.compile(
        r"\b(?:over|more\s+than|at\s+least|approx(?:imately)?)\s+(\d{1,2})\+?\s*(?:years?|yrs?)\b"
        r"(?:\s+(?:of|in|working\s+with))\s+[\w\s]{2,30}?(?:development|engineering|experience|role)\b",
        re.IGNORECASE
    ),
]


def extract_years_of_experience(text: Optional[str]) -> Optional[int]:
    """
    Extracts stated years of professional experience from resume text.
    Filters out academic degree durations and unrelated numbers.
    """
    if not isinstance(text, str) or not text.strip():
        return None

    detected_years = []

    for pattern in EXPERIENCE_YEARS_PATTERNS:
        matches = pattern.findall(text)
        for val in matches:
            if isinstance(val, tuple):
                val = next((v for v in val if v), "")
            if val and val.isdigit():
                years = int(val)
                if 1 <= years <= 40:
                    detected_years.append(years)

    return max(detected_years) if detected_years else None


def calculate_text_similarity(job_text: Optional[str], resume_text: Optional[str]) -> float:
    """
    Computes TF-IDF cosine similarity between job description and resume text.
    Safely handles None, empty, or non-string inputs without raising exceptions.
    Returns score scaled between 0.0 and 100.0.
    """
    if not isinstance(job_text, str) or not isinstance(resume_text, str):
        return 0.0

    j_clean = job_text.strip()
    r_clean = resume_text.strip()

    if not j_clean or not r_clean:
        return 0.0

    try:
        vectorizer = TfidfVectorizer(
            stop_words="english",
            ngram_range=(1, 2),
            sublinear_tf=True,
            max_features=5000
        )
        tfidf_matrix = vectorizer.fit_transform([j_clean, r_clean])
        similarity = float(cosine_similarity(tfidf_matrix[0:1], tfidf_matrix[1:2])[0][0])
        return round(min(max(similarity * 100.0, 0.0), 100.0), 2)
    except Exception:
        return 0.0


def calculate_skills_overlap(
    required_skills: List[str],
    candidate_skills: List[str]
) -> Tuple[float, List[str], List[str]]:
    """
    Calculates overlap between required and candidate skill sets.

    Returns:
        (skills_score: float, matched_skills: List[str], missing_skills: List[str])
    """
    if not required_skills:
        return 100.0, [], []

    # Case-insensitive map for required skills
    req_map = {s.strip().lower(): s.strip() for s in required_skills if s and s.strip()}
    cand_set = {s.strip().lower() for s in candidate_skills if s and s.strip()}

    matched = []
    missing = []

    for lower_req, original_req in req_map.items():
        if lower_req in cand_set:
            matched.append(original_req)
        else:
            missing.append(original_req)

    score = (len(matched) / len(req_map)) * 100.0 if req_map else 100.0
    return round(min(max(score, 0.0), 100.0), 2), sorted(matched), sorted(missing)


def calculate_experience_score(
    detected_years: Optional[int],
    min_required_years: Optional[int]
) -> float:
    """
    Calculates experience compliance score (0.0 to 100.0).
    """
    min_years = min_required_years or 0
    if min_years <= 0:
        return 100.0

    if detected_years is None:
        # Default baseline if years cannot be explicitly determined from text
        return 50.0

    if detected_years >= min_years:
        return 100.0

    # Proportional score if experience is below requirement
    ratio = detected_years / min_years
    return round(min(max(ratio * 100.0, 0.0), 100.0), 2)


def generate_evaluation_summary(
    match_score: float,
    skills_score: float,
    experience_score: float,
    matched_skills: List[str],
    missing_skills: List[str],
    detected_years: Optional[int],
    min_years: int
) -> str:
    """Generates an explainable human-readable AI assessment."""
    if match_score >= 80.0:
        level = "Strong Match"
    elif match_score >= 60.0:
        level = "Moderate Match"
    elif match_score >= 40.0:
        level = "Partial Match"
    else:
        level = "Low Match"

    parts = [f"{level} ({match_score:.1f}%)."]

    # Skill insights
    if matched_skills:
        top_matched = ", ".join(matched_skills[:5])
        parts.append(f"Demonstrates key skills: {top_matched}.")
    if missing_skills:
        top_missing = ", ".join(missing_skills[:5])
        parts.append(f"Missing required skills: {top_missing}.")

    # Experience insights
    if min_years > 0:
        if detected_years is not None:
            if detected_years >= min_years:
                parts.append(f"Experience ({detected_years}+ years) meets the minimum requirement of {min_years} years.")
            else:
                parts.append(f"Experience ({detected_years} years) is below the requested {min_years} years.")
        else:
            parts.append(f"Years of experience not explicitly stated (requested: {min_years} years).")

    return " ".join(parts)


def evaluate_match(request: MatchRequest) -> MatchResponse:
    """
    Evaluates a single resume against a job description.
    """
    job_desc = request.job_description or ""
    resume_text = request.resume_text or ""
    min_years = request.min_experience_years or 0

    # 1. Resolve required skills
    required_skills = list(request.required_skills)
    if not required_skills and job_desc:
        extracted_job_skills = extract_skills(job_desc)
        required_skills = [s.skill_name for s in extracted_job_skills]

    # 2. Resolve candidate skills
    candidate_skills = list(request.candidate_skills)
    if not candidate_skills and resume_text:
        extracted_cand_skills = extract_skills(resume_text)
        candidate_skills = [s.skill_name for s in extracted_cand_skills]

    # 3. Compute skill overlap
    skills_score, matched_skills, missing_skills = calculate_skills_overlap(
        required_skills=required_skills,
        candidate_skills=candidate_skills
    )

    # 4. Compute text semantic similarity
    text_similarity_score = calculate_text_similarity(job_desc, resume_text)

    # 5. Compute experience compliance
    detected_years = extract_years_of_experience(resume_text)
    experience_score = calculate_experience_score(detected_years, min_years)

    # 6. Composite weighted score
    if required_skills:
        final_score = (skills_score * 0.50) + (text_similarity_score * 0.30) + (experience_score * 0.20)
    else:
        final_score = (text_similarity_score * 0.70) + (experience_score * 0.30)

    final_score = round(min(max(final_score, 0.0), 100.0), 2)

    # 7. Generate summary
    summary = generate_evaluation_summary(
        match_score=final_score,
        skills_score=skills_score,
        experience_score=experience_score,
        matched_skills=matched_skills,
        missing_skills=missing_skills,
        detected_years=detected_years,
        min_years=min_years
    )

    return MatchResponse(
        job_id=request.job_id,
        resume_id=request.resume_id,
        match_score=final_score,
        skills_score=skills_score,
        experience_score=experience_score,
        matched_skills=matched_skills,
        missing_skills=missing_skills,
        ai_summary=summary
    )


def rank_candidates(request: BatchRankRequest) -> BatchRankResponse:
    """
    Ranks multiple candidates against a target job description.
    Sorts deterministically by:
      1. final_score (descending)
      2. skills_score (descending)
      3. experience_score (descending)
      4. candidate_name (alphabetical, case-insensitive)
      5. resume_id (numerical fallback)
      6. submission_order (original request order)
    """
    evaluated_candidates = []

    for index, cand in enumerate(request.candidates):
        cand_resume_id = cand.resume_id if cand.resume_id is not None else 0

        match_req = MatchRequest(
            job_id=request.job_id,
            job_title=request.job_title,
            job_description=request.job_description,
            required_skills=request.required_skills,
            min_experience_years=request.min_experience_years,
            resume_id=cand_resume_id,
            candidate_name=cand.candidate_name,
            resume_text=cand.resume_text,
            candidate_skills=cand.candidate_skills
        )
        match_res = evaluate_match(match_req)

        evaluated_candidates.append({
            "submission_order": index,
            "candidate_id": cand.candidate_id,
            "resume_id": cand_resume_id,
            "candidate_name": (cand.candidate_name or "").strip(),
            "final_score": match_res.match_score,
            "skills_score": match_res.skills_score,
            "experience_score": match_res.experience_score,
            "matched_skills": match_res.matched_skills,
            "missing_skills": match_res.missing_skills,
            "ai_summary": match_res.ai_summary
        })

    # Deterministic multi-tier sort
    evaluated_candidates.sort(
        key=lambda c: (
            -c["final_score"],
            -c["skills_score"],
            -c["experience_score"],
            c["candidate_name"].lower(),
            c["resume_id"],
            c["submission_order"]
        )
    )

    ranked_results: List[RankedCandidate] = []
    for rank_idx, cand_data in enumerate(evaluated_candidates, start=1):
        ranked_results.append(
            RankedCandidate(
                rank_position=rank_idx,
                candidate_id=cand_data["candidate_id"],
                resume_id=cand_data["resume_id"],
                candidate_name=cand_data["candidate_name"] if cand_data["candidate_name"] else None,
                final_score=cand_data["final_score"],
                skills_score=cand_data["skills_score"],
                experience_score=cand_data["experience_score"],
                matched_skills=cand_data["matched_skills"],
                missing_skills=cand_data["missing_skills"],
                ai_summary=cand_data["ai_summary"]
            )
        )

    return BatchRankResponse(
        job_id=request.job_id,
        total_candidates=len(ranked_results),
        rankings=ranked_results
    )
