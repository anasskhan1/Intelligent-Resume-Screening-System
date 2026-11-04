"""
Intelligent Candidate Contact Information Extractor Service.
Extracts candidate full name, contact email, and phone number from resume text.
Designed with high precision to avoid false positives (headings, job titles, dates, pin codes).
"""

import re
from typing import Dict, List, Optional, Tuple


# =============================================================================
# Curated Blacklists & Filters
# =============================================================================

# Common resume section titles, headings, and metadata markers to reject as names
REJECTED_NAME_HEADINGS = {
    "resume", "curriculum vitae", "cv", "biodata", "bio-data",
    "profile", "personal profile", "summary", "professional summary",
    "career summary", "executive summary", "objective", "career objective",
    "about me", "contact", "contact information", "contact info", "details",
    "personal details", "experience", "work experience", "professional experience",
    "employment history", "employment", "work history", "education",
    "academic background", "qualifications", "academic qualifications",
    "skills", "technical skills", "core competencies", "key skills",
    "areas of expertise", "expertise", "projects", "academic projects",
    "personal projects", "declaration", "references", "certifications",
    "licenses", "achievements", "honors", "awards", "publications",
    "languages", "hobbies", "interests", "activities", "strengths",
    "page", "page 1", "page 2", "page 1 of 1", "page 1 of 2"
}

# Common technical and professional job titles / designations to reject as names
REJECTED_JOB_TITLES = {
    "software engineer", "senior software engineer", "lead software engineer",
    "principal software engineer", "principal engineer", "staff software engineer",
    "software developer", "senior software developer", "lead software developer",
    "application developer", "full stack developer", "full-stack developer",
    "full stack engineer", "full-stack engineer", "backend developer",
    "back-end developer", "backend engineer", "back-end engineer",
    "frontend developer", "front-end developer", "frontend engineer",
    "front-end engineer", "java developer", "senior java developer",
    "python developer", "senior python developer", "dotnet developer",
    ".net developer", "c# developer", "c++ developer", "web developer",
    "mobile developer", "android developer", "ios developer",
    "data scientist", "senior data scientist", "lead data scientist",
    "data analyst", "senior data analyst", "data engineer", "senior data engineer",
    "machine learning engineer", "ml engineer", "ai engineer", "ai researcher",
    "devops engineer", "senior devops engineer", "devops specialist",
    "site reliability engineer", "sre", "cloud engineer", "cloud architect",
    "solutions architect", "solution architect", "enterprise architect",
    "technical architect", "system architect", "system administrator",
    "systems administrator", "sysadmin", "database administrator", "dba",
    "project manager", "technical project manager", "product manager",
    "program manager", "scrum master", "agile coach", "delivery manager",
    "qa engineer", "quality assurance engineer", "qa analyst", "test engineer",
    "software test engineer", "sdet", "automation engineer", "qa automation engineer",
    "consultant", "senior consultant", "associate", "analyst", "intern",
    "software engineer intern", "graduate trainee", "management trainee",
    "fresher", "entry level", "technical lead", "tech lead", "team lead",
    "engineering manager", "director of engineering", "vp engineering",
    "ui designer", "ux designer", "ui/ux designer", "product designer",
    "network engineer", "security engineer", "cybersecurity specialist"
}

# Standard titles/salutations to strip from extracted names
NAME_PREFIXES = {"dr.", "dr", "mr.", "mr", "ms.", "ms", "mrs.", "mrs", "prof.", "prof"}

# Disallowed company/administrative email address prefixes
GENERIC_EMAIL_PREFIXES = (
    "hr@", "careers@", "jobs@", "info@", "support@", "contact@",
    "admin@", "recruiting@", "help@", "sales@", "service@", "team@",
    "billing@", "press@", "marketing@", "feedback@", "enquiries@"
)

# Common technical words to reject as individual name tokens
TECHNICAL_KEYWORDS = {
    "java", "python", "javascript", "typescript", "html", "css", "sql", "mysql",
    "react", "angular", "vue", "node", "nodejs", "spring", "docker", "kubernetes",
    "aws", "azure", "gcp", "linux", "unix", "git", "github", "gitlab", "jira",
    "rest", "api", "apis", "soap", "json", "xml", "agile", "scrum", "ci", "cd",
    "developer", "engineer", "designer", "architect", "manager", "lead", "senior",
    "junior", "analyst", "consultant", "intern", "trainee", "fresher", "resume",
    "curriculum", "vitae", "portfolio", "email", "phone", "mobile", "address",
    "experience", "education", "skill", "skills", "project", "projects"
}


# =============================================================================
# 1. Candidate Name Extraction
# =============================================================================

def extract_candidate_name(text: str) -> Optional[str]:
    """
    Extracts the candidate's likely full name from the resume header (first 3-5 lines).
    Rejects job titles, section headings, email/phone lines, and skill names.
    Returns None if no candidate line meets high-confidence criteria.
    """
    if not text or not text.strip():
        return None

    # Inspect first 6 non-empty lines
    lines = [line.strip() for line in text.split("\n") if line.strip()]
    header_lines = lines[:6]

    for raw_line in header_lines:
        line = re.sub(r"\s+", " ", raw_line).strip()
        lower_line = line.lower()

        # 1. Reject lines that match generic headings
        if lower_line in REJECTED_NAME_HEADINGS:
            continue

        # 2. Reject lines that match job titles
        if lower_line in REJECTED_JOB_TITLES:
            continue

        # 3. Reject lines containing contact markers, links, or emails
        if "@" in line or "http://" in lower_line or "https://" in lower_line or "www." in lower_line:
            continue
        if "github.com" in lower_line or "linkedin.com" in lower_line:
            continue
        if any(marker in lower_line for marker in ["email:", "phone:", "mobile:", "mob:", "tel:", "address:", "location:", "dob:"]):
            continue

        # 4. Reject lines with pipe separators, slashes, or bullet characters (often contact or summary bars)
        if any(char in line for char in ["|", "/", "\\", "•", "★", "*", ";", "_", "=", "+"]):
            continue

        # 5. Reject lines that are too short (< 3 chars) or too long (> 45 chars)
        if len(line) < 3 or len(line) > 45:
            continue

        # 6. Reject lines where digits make up a noticeable portion
        digit_count = sum(c.isdigit() for c in line)
        if digit_count > 0:
            continue

        # 7. Strip title prefixes (e.g. Dr., Mr., Ms.)
        tokens = line.split(" ")
        if tokens and tokens[0].lower() in NAME_PREFIXES:
            tokens = tokens[1:]

        if len(tokens) < 2 or len(tokens) > 4:
            # Most personal names are 2 to 4 words
            continue

        # 8. Validate each name token
        is_valid_name = True
        normalized_tokens = []

        for token in tokens:
            clean_token = token.strip(" ,.")
            lower_token = clean_token.lower()

            # Each token must be alphabetic (allowing hyphens for double-barrel names like Jean-Pierre)
            if not re.match(r"^[A-Za-z]+(?:['\-][A-Za-z]+)*\.?$", token):
                is_valid_name = False
                break

            # Reject if token is a technical keyword or generic term
            if lower_token in TECHNICAL_KEYWORDS:
                is_valid_name = False
                break

            # Reject single-character tokens unless it is an initial like "A." or "A"
            if len(clean_token) == 1 and not token.endswith("."):
                # Single letter without dot is often a stray initial or artifact
                pass

            # Must have at least 1 character
            if not clean_token:
                is_valid_name = False
                break

            # Properly capitalize parts of hyphenated tokens (e.g. Jean-Pierre)
            capitalized_token = "-".join(part.capitalize() for part in clean_token.split("-"))
            normalized_tokens.append(capitalized_token)

        if not is_valid_name or len(normalized_tokens) < 2:
            continue

        # Check total character length of combined tokens
        candidate_name = " ".join(normalized_tokens)
        if len(candidate_name) < 4 or len(candidate_name) > 40:
            continue

        # Verified candidate full name
        return candidate_name

    return None


# =============================================================================
# 2. Email Address Extraction
# =============================================================================

EMAIL_REGEX = re.compile(
    r"\b[A-Za-z0-9](?:[A-Za-z0-9._%+-]*[A-Za-z0-9])?@[A-Za-z0-9.-]+\.[A-Za-z]{2,10}\b"
)

def extract_candidate_email(text: str) -> Optional[str]:
    """
    Extracts candidate email from resume text.
    Prioritizes header contact emails and prefers personal addresses over corporate prefixes.
    Normalizes to lowercase.
    """
    if not text or not text.strip():
        return None

    lines = [line.strip() for line in text.split("\n") if line.strip()]
    header_text = "\n".join(lines[:15])

    # 1. Search in header lines first (primary contact information area)
    header_matches = [m.strip().lower().rstrip(".,;)>") for m in EMAIL_REGEX.findall(header_text)]
    header_candidates = [e for e in header_matches if "." in e.split("@")[-1]]

    # If header candidates found, prefer personal over generic corporate prefixes
    personal_in_header = [e for e in header_candidates if not any(e.startswith(prefix) for prefix in GENERIC_EMAIL_PREFIXES)]
    if personal_in_header:
        return personal_in_header[0]
    if header_candidates:
        return header_candidates[0]

    # 2. Search entire document as fallback
    all_matches = [m.strip().lower().rstrip(".,;)>") for m in EMAIL_REGEX.findall(text)]
    all_candidates = [e for e in all_matches if "." in e.split("@")[-1]]

    personal_in_doc = [e for e in all_candidates if not any(e.startswith(prefix) for prefix in GENERIC_EMAIL_PREFIXES)]
    if personal_in_doc:
        return personal_in_doc[0]
    if all_candidates:
        return all_candidates[0]

    return None


# =============================================================================
# 3. Phone Number Extraction
# =============================================================================

# Comprehensive phone patterns matching:
# 1. Indian Mobile numbers (+91-9876543210, +91 98765 43210, 9876543210, 09876543210, +91 98765-43210)
# 2. International and US formats (+1 (555) 123-4567, +44 20 7946 0958, (555) 123-4567)
PHONE_CANDIDATE_REGEX = re.compile(
    r"""
    (?:
        # Explicit label prefix
        (?:(?:phone|mobile|mob|cell|tel|contact|call)\s*[:.-]?\s*)?
        # Number body
        (?:
            # International prefix (+91, +1, +44, etc.)
            (?:\+\d{1,3}[-.\s]*)?
            # Area code or Indian leading 0
            (?:\(?0?\d{1,4}\)?[-.\s]*)?
            # Main subscriber digits with optional hyphens/spaces
            [6-9]\d{2,4}[-.\s]*\d{4,5}
            |
            # Standard 10-digit North American or general format
            \+?1?[-.\s]*\(?[2-9]\d{2}\)?[-.\s]*[2-9]\d{2}[-.\s]*\d{4}
            |
            # General international grouped digits
            \+\d{1,3}[-.\s]*(?:\d{1,4}[-.\s]*){2,5}\d{2,4}
        )
    )
    """,
    re.VERBOSE | re.IGNORECASE
)

# Reject patterns that represent date ranges, years, GPA, or postal codes
DATE_RANGE_REGEX = re.compile(r"\b(?:19|20)\d{2}\s*[-–—/]\s*(?:(?:19|20)\d{2}|present|current)\b", re.IGNORECASE)
PINCODE_REGEX = re.compile(r"\b(?:pin|pincode|zip|zipcode|postal)\s*[:.-]?\s*\d{5,6}\b", re.IGNORECASE)
RATIO_REGEX = re.compile(r"\b\d{1,2}(?:\.\d+)?\s*/\s*(?:4|10|100)\b")


def normalize_phone_number(raw_phone: str) -> str:
    """
    Normalizes spacing and punctuation in extracted phone number.
    Removes leading label prefixes (Phone:, Mobile:, etc.).
    Preserves '+' for international prefix and clean formatting.
    """
    cleaned = re.sub(r"(?i)^(?:phone|mobile|mob|cell|tel|contact|call)\s*[:.-]?\s*", "", raw_phone).strip()
    # Normalize multiple whitespace
    cleaned = re.sub(r"[ \t]+", " ", cleaned)
    # If starting with +919876543210 (compact Indian mobile), format nicely with space
    if re.match(r"^\+91[6-9]\d{9}$", cleaned):
        cleaned = f"+91 {cleaned[3:]}"
    elif re.match(r"^91[6-9]\d{9}$", cleaned):
        cleaned = f"+91 {cleaned[2:]}"
    return cleaned.strip(" ,.-;")


def is_valid_phone_candidate(candidate: str, full_context_line: str) -> bool:
    """
    Verifies that a matched string is a genuine phone number and not a date, PIN code, or ratio.
    """
    digits_only = re.sub(r"\D", "", candidate)

    # Must contain between 10 and 15 digits (ITU-T E.164 standard)
    if len(digits_only) < 10 or len(digits_only) > 15:
        return False

    # Reject obvious dummy sequences (e.g. 0000000000, 1111111111, 1234567890)
    if len(set(digits_only)) <= 2:
        return False
    if digits_only in ("1234567890", "0123456789", "9876543210"):
        # Allow standard real numbers, but reject pure ascending sequences
        if digits_only == "1234567890":
            return False

    # Reject if the match is part of a date range
    if DATE_RANGE_REGEX.search(full_context_line):
        # Check if the digits overlap with the date
        for match in DATE_RANGE_REGEX.finditer(full_context_line):
            if any(char.isdigit() for char in match.group()):
                if match.group() in candidate or candidate in match.group():
                    return False

    # Reject if labeled as PIN code or postal code
    if PINCODE_REGEX.search(full_context_line):
        if len(digits_only) == 6:  # 6-digit Indian PIN code
            return False

    # Reject GPA/percentage ratio
    if RATIO_REGEX.search(full_context_line):
        if "/" in candidate:
            return False

    return True


def extract_candidate_phone(text: str) -> Optional[str]:
    """
    Extracts candidate contact phone number from resume text.
    Prioritizes numbers in header lines (first 15 lines) or labeled with contact keywords.
    """
    if not text or not text.strip():
        return None

    lines = [line.strip() for line in text.split("\n") if line.strip()]

    # 1. First pass: look in header lines (top 15 lines)
    header_lines = lines[:15]
    for line in header_lines:
        for match in PHONE_CANDIDATE_REGEX.finditer(line):
            raw = match.group().strip()
            if is_valid_phone_candidate(raw, line):
                return normalize_phone_number(raw)

    # 2. Second pass: look for explicitly labeled phone anywhere in document
    for line in lines:
        lower_line = line.lower()
        if any(marker in lower_line for marker in ["phone", "mobile", "mob:", "cell", "tel:"]):
            for match in PHONE_CANDIDATE_REGEX.finditer(line):
                raw = match.group().strip()
                if is_valid_phone_candidate(raw, line):
                    return normalize_phone_number(raw)

    # 3. Third pass: check remaining lines if not found yet
    for line in lines[15:]:
        for match in PHONE_CANDIDATE_REGEX.finditer(line):
            raw = match.group().strip()
            if is_valid_phone_candidate(raw, line):
                return normalize_phone_number(raw)

    return None


# =============================================================================
# Composite Contact Extraction
# =============================================================================

def extract_contact_info(text: str) -> Dict[str, Optional[str]]:
    """
    Extracts all candidate contact fields in one call.

    Returns:
        {
            "candidate_name": Optional[str],
            "candidate_email": Optional[str],
            "candidate_phone": Optional[str]
        }
    """
    return {
        "candidate_name": extract_candidate_name(text),
        "candidate_email": extract_candidate_email(text),
        "candidate_phone": extract_candidate_phone(text)
    }
