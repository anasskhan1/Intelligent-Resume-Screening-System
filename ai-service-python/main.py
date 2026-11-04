"""
FastAPI Entrypoint for AI Resume Screening and Candidate Ranking Service.
Communicates strictly via HTTP with the Java Spring Boot backend.
"""

import logging
import os
import sys
from pathlib import Path
from typing import Any, Dict, Optional

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware

# Ensure the ai-service-python directory is always on sys.path for top-level module resolution
CURRENT_DIR = Path(__file__).resolve().parent
if str(CURRENT_DIR) not in sys.path:
    sys.path.insert(0, str(CURRENT_DIR))

# Load environment configuration from .env file (checked in current dir, then parent)
load_dotenv(CURRENT_DIR / ".env")
load_dotenv(CURRENT_DIR.parent / ".env")

# =============================================================================
# Environment Configuration with Safe Development Defaults
# =============================================================================
AI_HOST = os.getenv("AI_SERVICE_HOST", os.getenv("HOST", "0.0.0.0"))
AI_PORT = int(os.getenv("PORT", os.getenv("AI_SERVICE_PORT", "10000")))
BACKEND_SERVICE_URL = os.getenv("BACKEND_SERVICE_URL", "http://localhost:8080")
TEMP_UPLOAD_DIR = Path(os.getenv("TEMP_UPLOAD_DIR", str(CURRENT_DIR / "temp"))).resolve()

# Maximum allowable upload size in bytes (defaults to 10 MB)
MAX_UPLOAD_SIZE_BYTES = int(os.getenv("MAX_UPLOAD_SIZE_BYTES", str(10 * 1024 * 1024)))

# Logging configuration
LOG_LEVEL_NAME = os.getenv("LOG_LEVEL", "INFO").upper()
LOG_LEVEL = getattr(logging, LOG_LEVEL_NAME, logging.INFO)
logging.basicConfig(
    level=LOG_LEVEL,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("ai_service")

# CORS Configuration - Strict explicit origins (no wildcards)
def _normalize_origin(origin: str) -> str:
    return origin.strip().rstrip("/")

raw_origins = os.getenv("ALLOWED_ORIGINS", os.getenv("CORS_ORIGINS", ""))
if raw_origins and raw_origins.strip():
    ALLOWED_ORIGINS = [_normalize_origin(orig) for orig in raw_origins.split(",") if orig.strip()]
else:
    ALLOWED_ORIGINS = [
        "http://localhost:8080",
        "http://127.0.0.1:8080",
        "http://localhost:8000",
        "http://127.0.0.1:8000",
        "http://localhost:5500",
        "http://127.0.0.1:5500",
    ]

# =============================================================================
# Service-to-Service Security & Authentication
# =============================================================================
AI_SERVICE_API_KEY = os.getenv("AI_SERVICE_API_KEY", "").strip()

def verify_service_api_key(
    x_api_key: Optional[str] = Header(None, alias="X-API-Key"),
    authorization: Optional[str] = Header(None, alias="Authorization"),
) -> None:
    """
    Enforces service-to-service authentication when AI_SERVICE_API_KEY is configured.
    Permits unauthenticated calls if AI_SERVICE_API_KEY is unset (backward-compatible for local dev/tests).
    Accepts credentials via X-API-Key header or Authorization: Bearer <key>.
    """
    if not AI_SERVICE_API_KEY:
        return

    provided_token = None
    if x_api_key:
        provided_token = x_api_key.strip()
    elif authorization and authorization.lower().startswith("bearer "):
        provided_token = authorization[7:].strip()

    if not provided_token or provided_token != AI_SERVICE_API_KEY:
        logger.warning("Rejected unauthorized request to AI service: invalid or missing API key")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Unauthorized: Invalid or missing service API key",
        )

try:
    from models.schemas import (
        BatchRankRequest,
        BatchRankResponse,
        MatchRequest,
        MatchResponse,
        ParseTextRequest,
        ResumeParseResponse,
    )
    from services.contact_extractor import extract_contact_info
    from services.extractor import extract_skills
    from services.matcher import evaluate_match, rank_candidates
    from services.parser import parse_plain_text, parse_resume_document
except ImportError:
    from .models.schemas import (
        BatchRankRequest,
        BatchRankResponse,
        MatchRequest,
        MatchResponse,
        ParseTextRequest,
        ResumeParseResponse,
    )
    from .services.contact_extractor import extract_contact_info
    from .services.extractor import extract_skills
    from .services.matcher import evaluate_match, rank_candidates
    from .services.parser import parse_plain_text, parse_resume_document

app = FastAPI(
    title="Intelligent Resume Screening AI Service",
    description="NLP-driven resume parsing, skill extraction, matching, and candidate ranking engine.",
    version="1.0.0"
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["Content-Type", "Authorization", "Accept"],
)


# =============================================================================
# Health & Status Endpoints
# =============================================================================

@app.get("/", tags=["Health"])
@app.get("/health", tags=["Health"])
async def health_check() -> Dict[str, Any]:
    """Liveness check for the Spring Boot backend or orchestrator."""
    return {
        "status": "UP",
        "service": "ai-service-python",
        "version": "1.0.0"
    }


# =============================================================================
# Resume Document & Text Parsing Endpoints
# =============================================================================

@app.post(
    "/api/v1/parse-document",
    response_model=ResumeParseResponse,
    dependencies=[Depends(verify_service_api_key)],
    tags=["Resume Parsing"],
    summary="Parse uploaded binary resume (PDF, DOCX, TXT)"
)
@app.post("/parse-document", response_model=ResumeParseResponse, dependencies=[Depends(verify_service_api_key)], include_in_schema=False)
def parse_document(file: UploadFile = File(...)) -> ResumeParseResponse:
    """
    Extracts plain text and structured skills from an uploaded resume file.
    Validates file size and format, returning safe error messages on failure.
    Executed synchronously in FastAPI's worker thread pool to prevent event loop blocking.
    """
    filename = file.filename or "uploaded_file"
    logger.info("Processing resume document upload: %s", filename)

    try:
        # Pre-check size header if provided by client
        if file.size is not None and file.size > MAX_UPLOAD_SIZE_BYTES:
            logger.warning(
                "Upload rejected for '%s': advertised size %d bytes exceeds %d bytes limit",
                filename,
                file.size,
                MAX_UPLOAD_SIZE_BYTES
            )
            return ResumeParseResponse(
                file_name=filename,
                parsed_text="",
                extracted_skills=[],
                word_count=0,
                success=False,
                error_message=f"File exceeds maximum allowed size of {MAX_UPLOAD_SIZE_BYTES // (1024 * 1024)} MB."
            )

        # Read up to limit + 1 byte synchronously to prevent memory exhaustion on oversized streams
        file_bytes = file.file.read(MAX_UPLOAD_SIZE_BYTES + 1)
        if not file_bytes:
            return ResumeParseResponse(
                file_name=filename,
                parsed_text="",
                extracted_skills=[],
                word_count=0,
                success=False,
                error_message=f"File '{filename}' is empty (0 bytes)."
            )

        if len(file_bytes) > MAX_UPLOAD_SIZE_BYTES:
            logger.warning(
                "Upload rejected for '%s': byte size exceeds %d bytes limit",
                filename,
                MAX_UPLOAD_SIZE_BYTES
            )
            return ResumeParseResponse(
                file_name=filename,
                parsed_text="",
                extracted_skills=[],
                word_count=0,
                success=False,
                error_message=f"File exceeds maximum allowed size of {MAX_UPLOAD_SIZE_BYTES // (1024 * 1024)} MB."
            )

        success, clean_text, error_msg = parse_resume_document(file_bytes, filename)
        if not success or not clean_text:
            return ResumeParseResponse(
                file_name=filename,
                parsed_text="",
                extracted_skills=[],
                word_count=0,
                success=False,
                error_message=error_msg or "Failed to extract readable text from document."
            )

        skills = extract_skills(clean_text)
        word_count = len(clean_text.split())
        contact_info = extract_contact_info(clean_text)

        return ResumeParseResponse(
            file_name=filename,
            parsed_text=clean_text,
            extracted_skills=skills,
            word_count=word_count,
            candidate_name=contact_info.get("candidate_name"),
            candidate_email=contact_info.get("candidate_email"),
            candidate_phone=contact_info.get("candidate_phone"),
            success=True,
            error_message=None
        )
    except Exception as exc:
        logger.error("Unexpected error parsing file '%s': %s", filename, str(exc), exc_info=True)
        return ResumeParseResponse(
            file_name=filename,
            parsed_text="",
            extracted_skills=[],
            word_count=0,
            success=False,
            error_message="An unexpected error occurred while processing the resume document."
        )


@app.post(
    "/api/v1/parse-text",
    response_model=ResumeParseResponse,
    dependencies=[Depends(verify_service_api_key)],
    tags=["Resume Parsing"],
    summary="Parse raw resume text"
)
@app.post("/parse-text", response_model=ResumeParseResponse, dependencies=[Depends(verify_service_api_key)], include_in_schema=False)
def parse_text(request: ParseTextRequest) -> ResumeParseResponse:
    """
    Cleans direct resume text and extracts granular skills.
    Executed synchronously in FastAPI's worker thread pool to prevent event loop blocking.
    """
    filename = request.file_name or "raw_input.txt"
    logger.info("Processing raw resume text for source: %s", filename)

    try:
        success, clean_text, error_msg = parse_plain_text(request.text, filename)
        if not success or not clean_text:
            return ResumeParseResponse(
                file_name=filename,
                parsed_text="",
                extracted_skills=[],
                word_count=0,
                success=False,
                error_message=error_msg or "Text content could not be processed."
            )

        skills = extract_skills(clean_text)
        word_count = len(clean_text.split())
        contact_info = extract_contact_info(clean_text)

        return ResumeParseResponse(
            file_name=filename,
            parsed_text=clean_text,
            extracted_skills=skills,
            word_count=word_count,
            candidate_name=contact_info.get("candidate_name"),
            candidate_email=contact_info.get("candidate_email"),
            candidate_phone=contact_info.get("candidate_phone"),
            success=True,
            error_message=None
        )
    except Exception as exc:
        logger.error("Error processing raw text: %s", str(exc), exc_info=True)
        return ResumeParseResponse(
            file_name=filename,
            parsed_text="",
            extracted_skills=[],
            word_count=0,
            success=False,
            error_message="An unexpected error occurred while processing the resume text."
        )


# =============================================================================
# Resume Screening & Candidate Matching Endpoints
# =============================================================================

@app.post(
    "/api/v1/match",
    response_model=MatchResponse,
    dependencies=[Depends(verify_service_api_key)],
    tags=["Matching & Screening"],
    summary="Screen a single resume against a job description"
)
@app.post("/match", response_model=MatchResponse, dependencies=[Depends(verify_service_api_key)], include_in_schema=False)
def match_resume(request: MatchRequest) -> MatchResponse:
    """
    Evaluates candidate fit against job requirements using skill overlap, TF-IDF, and experience.
    Executed synchronously in FastAPI's worker thread pool to prevent event loop blocking.
    """
    logger.info(
        "Screening match for Job ID %s vs Resume ID %s",
        request.job_id,
        request.resume_id
    )
    try:
        return evaluate_match(request)
    except Exception as exc:
        logger.error("Error during match evaluation: %s", str(exc), exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="An error occurred while evaluating the resume match."
        )


# =============================================================================
# Batch Candidate Ranking Endpoints
# =============================================================================

@app.post(
    "/api/v1/rank",
    response_model=BatchRankResponse,
    dependencies=[Depends(verify_service_api_key)],
    tags=["Ranking"],
    summary="Screen and rank candidate leaderboard for a target job"
)
@app.post("/rank", response_model=BatchRankResponse, dependencies=[Depends(verify_service_api_key)], include_in_schema=False)
def rank_candidate_pool(request: BatchRankRequest) -> BatchRankResponse:
    """
    Ranks multiple candidate profiles deterministically against a target job.
    Executed synchronously in FastAPI's worker thread pool to prevent event loop blocking.
    """
    logger.info(
        "Ranking %d candidate(s) for Job ID %s ('%s')",
        len(request.candidates),
        request.job_id,
        request.job_title
    )
    try:
        return rank_candidates(request)
    except Exception as exc:
        logger.error("Error during candidate batch ranking: %s", str(exc), exc_info=True)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="An error occurred while ranking candidate profiles."
        )


if __name__ == "__main__":
    import uvicorn
    logger.info("Starting AI microservice on %s:%d", AI_HOST, AI_PORT)
    uvicorn.run(app, host=AI_HOST, port=AI_PORT)

