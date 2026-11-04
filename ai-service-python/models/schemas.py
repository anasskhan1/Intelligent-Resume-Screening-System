"""
Pydantic Schemas for AI Resume Screening and Ranking Service.
Aligns with the MySQL schema and Java Spring Boot REST client contracts.
"""

from typing import List, Optional
from pydantic import BaseModel, Field, ConfigDict


# =============================================================================
# 1. Resume Parsing & Skill Extraction Schemas
# =============================================================================

class ExtractedSkill(BaseModel):
    """Granular skill extracted from resume text."""
    model_config = ConfigDict(from_attributes=True)

    skill_name: str = Field(..., description="Normalized name of the detected skill")
    category: Optional[str] = Field(None, description="Category (e.g. Technical, Framework, Tool, Soft Skill)")
    confidence_score: Optional[float] = Field(None, ge=0.0, le=100.0, description="Confidence percentage (0-100)")


class ParseTextRequest(BaseModel):
    """Direct raw text submission for parsing and skill extraction."""
    text: str = Field(..., min_length=1, description="Raw text content of the resume")
    file_name: Optional[str] = Field("raw_input.txt", description="Originating filename if available")


class ResumeParseResponse(BaseModel):
    """Output contract for resume document/text parsing."""
    model_config = ConfigDict(from_attributes=True)

    file_name: str = Field(..., description="Processed resume filename")
    parsed_text: str = Field(..., description="Extracted clean plain text from resume")
    extracted_skills: List[ExtractedSkill] = Field(default_factory=list, description="Extracted skills list")
    word_count: int = Field(default=0, ge=0, description="Total word count in extracted text")
    candidate_name: Optional[str] = Field(None, description="Extracted candidate full name")
    candidate_email: Optional[str] = Field(None, description="Extracted candidate contact email")
    candidate_phone: Optional[str] = Field(None, description="Extracted candidate contact phone number")
    success: bool = Field(default=True, description="Indicates if parsing completed successfully")
    error_message: Optional[str] = Field(None, description="Error detail if parsing encountered issues")


# =============================================================================
# 2. Job-Resume Match & Screening Schemas
# =============================================================================

class MatchRequest(BaseModel):
    """Input payload to evaluate a single candidate resume against a job description."""
    job_id: Optional[int] = Field(None, description="Database ID of the job description")
    job_title: str = Field(..., min_length=1, description="Title of the job position")
    job_description: str = Field(..., min_length=1, description="Full job description text")
    required_skills: List[str] = Field(default_factory=list, description="List of required/preferred skills")
    min_experience_years: Optional[int] = Field(0, ge=0, description="Minimum required years of experience")

    resume_id: Optional[int] = Field(None, description="Database ID of the resume")
    candidate_name: Optional[str] = Field(None, description="Candidate full name")
    resume_text: str = Field(..., min_length=1, description="Clean text extracted from candidate resume")
    candidate_skills: List[str] = Field(default_factory=list, description="Extracted candidate skills")


class MatchResponse(BaseModel):
    """Detailed screening evaluation matching the screening_results table schema."""
    model_config = ConfigDict(from_attributes=True)

    job_id: Optional[int] = Field(None, description="Job description identifier")
    resume_id: Optional[int] = Field(None, description="Resume identifier")
    match_score: float = Field(..., ge=0.0, le=100.0, description="Overall weighted match percentage (0-100)")
    skills_score: float = Field(..., ge=0.0, le=100.0, description="Skill similarity match percentage (0-100)")
    experience_score: float = Field(..., ge=0.0, le=100.0, description="Experience fit score (0-100)")
    matched_skills: List[str] = Field(default_factory=list, description="Skills present in both job and resume")
    missing_skills: List[str] = Field(default_factory=list, description="Required skills missing from resume")
    ai_summary: str = Field(..., description="AI evaluation feedback and rationale")


# =============================================================================
# 3. Candidate Ranking Schemas
# =============================================================================

class CandidateProfile(BaseModel):
    """Candidate resume profile for batch ranking evaluation."""
    candidate_id: Optional[int] = Field(None, description="Database candidate identifier")
    resume_id: int = Field(..., description="Database resume identifier")
    candidate_name: Optional[str] = Field(None, description="Full name of the candidate")
    resume_text: str = Field(..., min_length=1, description="Parsed resume text")
    candidate_skills: List[str] = Field(default_factory=list, description="Candidate skills list")


class BatchRankRequest(BaseModel):
    """Input payload to screen and rank multiple candidates against a target job."""
    job_id: Optional[int] = Field(None, description="Job description identifier")
    job_title: str = Field(..., min_length=1, description="Title of the job position")
    job_description: str = Field(..., min_length=1, description="Full text description of the target job")
    required_skills: List[str] = Field(default_factory=list, description="Required skills for the job")
    min_experience_years: Optional[int] = Field(0, ge=0, description="Minimum years of experience")
    candidates: List[CandidateProfile] = Field(..., min_length=1, description="List of candidates to rank")


class RankedCandidate(BaseModel):
    """Ranked leaderboard candidate entry matching candidate_rankings table schema."""
    model_config = ConfigDict(from_attributes=True)

    rank_position: int = Field(..., ge=1, description="Rank position (1 = top candidate)")
    candidate_id: Optional[int] = Field(None, description="Candidate ID")
    resume_id: int = Field(..., description="Resume ID")
    candidate_name: Optional[str] = Field(None, description="Candidate full name")
    final_score: float = Field(..., ge=0.0, le=100.0, description="Final composite score (0-100)")
    skills_score: float = Field(..., ge=0.0, le=100.0, description="Skills score (0-100)")
    experience_score: float = Field(..., ge=0.0, le=100.0, description="Experience score (0-100)")
    matched_skills: List[str] = Field(default_factory=list, description="Matched skills")
    missing_skills: List[str] = Field(default_factory=list, description="Missing skills")
    ai_summary: str = Field(..., description="Summary justification for the rank")


class BatchRankResponse(BaseModel):
    """Output ranking leaderboard for a given job."""
    model_config = ConfigDict(from_attributes=True)

    job_id: Optional[int] = Field(None, description="Target job identifier")
    total_candidates: int = Field(..., ge=0, description="Number of candidates screened and ranked")
    rankings: List[RankedCandidate] = Field(default_factory=list, description="Ordered candidate leaderboard")
