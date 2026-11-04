"""
Rule-Based Skill and Entity Extraction Service.
Extracts normalized technical and professional skills from resume text.

Confidence Scoring Heuristic:
The confidence score produced by this module is a deterministic, explainable heuristic
ranking metric (bounded between 0.0 and 100.0) reflecting keyword prominence, recurrence,
and section locality. It is an explainable heuristic weight for candidate screening,
NOT a calibrated statistical or Bayesian probability.
"""

import re
from typing import Dict, List, Optional, Pattern, Tuple

try:
    from models.schemas import ExtractedSkill
except ImportError:
    from ..models.schemas import ExtractedSkill


# =============================================================================
# Skill Definition Specification
# Separates case-insensitive general aliases, strict case-sensitive aliases
# (e.g., standalone 'JS' and 'TS'), and section-only aliases for ambiguous words.
# =============================================================================

class SkillSpec:
    """Specification defining canonical skill name, category, and alias match rules."""
    def __init__(
        self,
        canonical_name: str,
        category: str,
        general_aliases: List[str],
        case_sensitive_aliases: Optional[List[str]] = None,
        section_only_aliases: Optional[List[str]] = None
    ):
        self.canonical_name = canonical_name
        self.category = category
        self.general_aliases = general_aliases
        self.case_sensitive_aliases = case_sensitive_aliases or []
        self.section_only_aliases = section_only_aliases or []


# =============================================================================
# Curated Skill Taxonomy
# =============================================================================

TAXONOMY_SPECS: List[SkillSpec] = [
    # Programming Languages
    SkillSpec(
        "Python", "Programming Language",
        general_aliases=["python", "python3", "python 3", "python 2", "cpython"]
    ),
    SkillSpec(
        "Java", "Programming Language",
        # \bjava\b(?!\s*script) guarantees whole-word matching while rejecting any JavaScript forms
        general_aliases=[r"\bjava\b(?!\s*script)", "core java", "j2ee", "jakarta ee", "java developer", "java backend", "java ee"]
    ),
    SkillSpec(
        "JavaScript", "Programming Language",
        general_aliases=["javascript", "ecmascript", "vanilla js", "modern js", "es6", "es6+", "es2015", "es2020", "java script"],
        case_sensitive_aliases=[r"\bJS\b"]  # Strictly case-sensitive: matches 'JS', rejects 'js'
    ),
    SkillSpec(
        "TypeScript", "Programming Language",
        general_aliases=["typescript", "ts language", "ts script"],
        case_sensitive_aliases=[r"\bTS\b"]  # Strictly case-sensitive: matches 'TS', rejects 'ts'
    ),
    SkillSpec(
        "C++", "Programming Language",
        general_aliases=["c++", "cpp"]
    ),
    SkillSpec(
        "C#", "Programming Language",
        general_aliases=["c#", "csharp", "c-sharp"]
    ),
    SkillSpec(
        "Go", "Programming Language",
        general_aliases=["golang", "go language", "go programming", "go developer"],
        section_only_aliases=["go"]
    ),
    SkillSpec(
        "Rust", "Programming Language",
        general_aliases=["rust language", "rustlang", "rust programming"],
        section_only_aliases=["rust"]
    ),
    SkillSpec(
        "PHP", "Programming Language",
        general_aliases=["php", "php7", "php8", "lamp stack"]
    ),
    SkillSpec(
        "Ruby", "Programming Language",
        general_aliases=["ruby", "ruby on rails"],
        section_only_aliases=["rails"]
    ),
    SkillSpec(
        "Swift", "Programming Language",
        general_aliases=["swift language", "swiftui", "swift programming"]
    ),
    SkillSpec(
        "Kotlin", "Programming Language",
        general_aliases=["kotlin"]
    ),
    SkillSpec(
        "Scala", "Programming Language",
        general_aliases=["scala"]
    ),
    SkillSpec(
        "SQL", "Programming Language",
        general_aliases=["sql", "structured query language", "ansi sql"]
    ),
    SkillSpec(
        "HTML", "Programming Language",
        general_aliases=["html", "html5"]
    ),
    SkillSpec(
        "CSS", "Programming Language",
        general_aliases=["css", "css3"]
    ),
    SkillSpec(
        "Bash", "Programming Language",
        general_aliases=["bash", "shell script", "shell scripting", "powershell"]
    ),
    SkillSpec(
        "Dart", "Programming Language",
        general_aliases=["dart language", "dart programming"]
    ),
    SkillSpec(
        "R", "Programming Language",
        general_aliases=["r programming", "r language", "r-project", "r script"]
    ),

    # Frameworks & Libraries
    SkillSpec(
        "Spring Boot", "Framework & Library",
        general_aliases=["spring boot", "springboot"]
    ),
    SkillSpec(
        "Spring Framework", "Framework & Library",
        general_aliases=["spring framework", "spring mvc", "spring data", "spring security"]
    ),
    SkillSpec(
        "React", "Framework & Library",
        general_aliases=[
            "react.js", "reactjs", "react-dom", "react native", "react library",
            "react framework", "react developer", "react frontend", "react/redux", "react/native"
        ],
        section_only_aliases=["react"]
    ),
    SkillSpec(
        "Angular", "Framework & Library",
        general_aliases=["angular", "angularjs", "angular.js", "angular 2+"]
    ),
    SkillSpec(
        "Vue.js", "Framework & Library",
        general_aliases=["vue.js", "vuejs", "vue 3", "vue 2", "vue framework", "vuex"],
        section_only_aliases=["vue"]
    ),
    SkillSpec(
        "Node.js", "Framework & Library",
        general_aliases=["node.js", "nodejs", "node js", "node runtime", "node server", "node backend", "node/express"],
        section_only_aliases=["node"]
    ),
    SkillSpec(
        "Express.js", "Framework & Library",
        general_aliases=["express.js", "expressjs", "express framework"],
        section_only_aliases=["express"]
    ),
    SkillSpec(
        "Django", "Framework & Library",
        general_aliases=["django", "django rest framework", "drf"]
    ),
    SkillSpec(
        "Flask", "Framework & Library",
        general_aliases=["flask", "flask framework"]
    ),
    SkillSpec(
        "FastAPI", "Framework & Library",
        general_aliases=["fastapi", "fast-api"]
    ),
    SkillSpec(
        "Next.js", "Framework & Library",
        general_aliases=["next.js", "nextjs"]
    ),
    SkillSpec(
        ".NET", "Framework & Library",
        general_aliases=[".net core", "asp.net core", "asp.net", "dotnet", ".net", "vb.net"]
    ),
    SkillSpec(
        "PyTorch", "Framework & Library",
        general_aliases=["pytorch"]
    ),
    SkillSpec(
        "TensorFlow", "Framework & Library",
        general_aliases=["tensorflow", "tf framework"]
    ),
    SkillSpec(
        "Scikit-learn", "Framework & Library",
        general_aliases=["scikit-learn", "sklearn"]
    ),
    SkillSpec(
        "Pandas", "Framework & Library",
        general_aliases=["pandas"]
    ),
    SkillSpec(
        "NumPy", "Framework & Library",
        general_aliases=["numpy"]
    ),
    SkillSpec(
        "Tailwind CSS", "Framework & Library",
        general_aliases=["tailwind css", "tailwind", "tailwindcss"]
    ),
    SkillSpec(
        "Bootstrap", "Framework & Library",
        general_aliases=["bootstrap framework", "bootstrap css", "bootstrap 5", "bootstrap 4", "bootstrap"]
    ),
    SkillSpec(
        "Hibernate", "Framework & Library",
        general_aliases=["hibernate", "hibernate orm"]
    ),
    SkillSpec(
        "JPA", "Framework & Library",
        general_aliases=["jpa", "java persistence api"]
    ),

    # Databases
    SkillSpec(
        "MySQL", "Database",
        general_aliases=["mysql"]
    ),
    SkillSpec(
        "PostgreSQL", "Database",
        general_aliases=["postgresql", "postgres", "psql"]
    ),
    SkillSpec(
        "MongoDB", "Database",
        general_aliases=["mongodb", "mongo db", "mongo"]
    ),
    SkillSpec(
        "Redis", "Database",
        general_aliases=["redis"]
    ),
    SkillSpec(
        "SQLite", "Database",
        general_aliases=["sqlite"]
    ),
    SkillSpec(
        "Oracle Database", "Database",
        general_aliases=["oracle db", "oracle database", "pl/sql"]
    ),
    SkillSpec(
        "Microsoft SQL Server", "Database",
        general_aliases=["sql server", "mssql", "t-sql"]
    ),
    SkillSpec(
        "Cassandra", "Database",
        general_aliases=["cassandra", "apache cassandra"]
    ),
    SkillSpec(
        "DynamoDB", "Database",
        general_aliases=["dynamodb", "amazon dynamodb"]
    ),
    SkillSpec(
        "Elasticsearch", "Database",
        general_aliases=["elasticsearch", "elastic search"]
    ),

    # Cloud, DevOps & Tools
    SkillSpec(
        "Amazon Web Services (AWS)", "Cloud & DevOps",
        general_aliases=["aws", "amazon web services", "aws ec2", "aws s3", "aws lambda", "aws cloud"]
    ),
    SkillSpec(
        "Microsoft Azure", "Cloud & DevOps",
        general_aliases=["azure", "microsoft azure", "azure devops"]
    ),
    SkillSpec(
        "Google Cloud Platform (GCP)", "Cloud & DevOps",
        general_aliases=["gcp", "google cloud", "google cloud platform"]
    ),
    SkillSpec(
        "Docker", "Cloud & DevOps",
        general_aliases=["docker", "dockerfile", "docker-compose", "docker container", "docker containers"]
    ),
    SkillSpec(
        "Kubernetes", "Cloud & DevOps",
        general_aliases=["kubernetes", "k8s"]
    ),
    SkillSpec(
        "Jenkins", "Cloud & DevOps",
        general_aliases=["jenkins"]
    ),
    SkillSpec(
        "Git", "Cloud & DevOps",
        general_aliases=["git", "github", "gitlab", "bitbucket"]
    ),
    SkillSpec(
        "CI/CD", "Cloud & DevOps",
        general_aliases=["ci/cd", "cicd", "continuous integration", "continuous deployment"]
    ),
    SkillSpec(
        "Linux", "Cloud & DevOps",
        general_aliases=["linux", "ubuntu", "debian", "centos", "redhat", "unix"]
    ),
    SkillSpec(
        "Terraform", "Cloud & DevOps",
        general_aliases=["terraform"]
    ),
    SkillSpec(
        "Ansible", "Cloud & DevOps",
        general_aliases=["ansible"]
    ),
    SkillSpec(
        "Nginx", "Cloud & DevOps",
        general_aliases=["nginx"]
    ),
    SkillSpec(
        "Apache Kafka", "Cloud & DevOps",
        general_aliases=["apache kafka", "kafka"]
    ),
    SkillSpec(
        "RabbitMQ", "Cloud & DevOps",
        general_aliases=["rabbitmq"]
    ),
    SkillSpec(
        "Maven", "Cloud & DevOps",
        general_aliases=["maven", "apache maven"]
    ),

    # Architecture & Methodologies
    SkillSpec(
        "RESTful APIs", "Architecture & Methodology",
        general_aliases=["rest api", "restful api", "rest apis", "restful", "rest web services", "rest endpoints", "restful services"]
    ),
    SkillSpec(
        "GraphQL", "Architecture & Methodology",
        general_aliases=["graphql"]
    ),
    SkillSpec(
        "Microservices", "Architecture & Methodology",
        general_aliases=["microservices", "microservice architecture"]
    ),
    SkillSpec(
        "Agile", "Architecture & Methodology",
        general_aliases=["agile", "agile methodology", "agile development", "agile/scrum"]
    ),
    SkillSpec(
        "Scrum", "Architecture & Methodology",
        general_aliases=["scrum", "scrum master", "daily scrum", "scrum methodology"]
    ),
    SkillSpec(
        "Object-Oriented Programming (OOP)", "Architecture & Methodology",
        general_aliases=["oop", "object-oriented programming", "object oriented"]
    ),
    SkillSpec(
        "Test-Driven Development (TDD)", "Architecture & Methodology",
        general_aliases=["tdd", "test-driven development"]
    ),
    SkillSpec(
        "Unit Testing", "Architecture & Methodology",
        general_aliases=["unit testing", "unit tests", "junit", "pytest", "mockito"]
    ),

    # Soft Skills
    SkillSpec(
        "Problem Solving", "Soft Skill",
        general_aliases=["problem solving", "analytical skills", "troubleshooting"]
    ),
    SkillSpec(
        "Leadership", "Soft Skill",
        general_aliases=["leadership", "team leadership", "mentoring"]
    ),
    SkillSpec(
        "Communication", "Soft Skill",
        general_aliases=["communication skills", "written communication", "verbal communication"]
    ),
    SkillSpec(
        "Teamwork", "Soft Skill",
        general_aliases=["teamwork", "team player", "collaboration", "cross-functional collaboration"]
    ),
    SkillSpec(
        "Critical Thinking", "Soft Skill",
        general_aliases=["critical thinking"]
    ),
    SkillSpec(
        "Project Management", "Soft Skill",
        general_aliases=["project management", "project planning"]
    ),
    SkillSpec(
        "Time Management", "Soft Skill",
        general_aliases=["time management", "prioritization"]
    ),
]


# Section header patterns commonly found in resumes
SKILLS_SECTION_PATTERN = re.compile(
    r"(?:skills|technical skills|technologies|core competencies|areas of expertise|proficiencies|"
    r"tools & technologies|tools and technologies|technical proficiencies)[\s:]*\n([\s\S]*?)"
    r"(?=\n[A-Z\s]{3,25}(?:\n|:)|$)",
    re.IGNORECASE
)


def _build_alias_regex(alias: str) -> str:
    """Formats an alias with appropriate word or non-alphanumeric boundaries."""
    if alias.startswith(r"\b"):
        return alias
    if re.fullmatch(r"[\w\s-]+", alias):
        return rf"\b{re.escape(alias)}\b"
    return rf"(?<![a-zA-Z0-9]){re.escape(alias)}(?![a-zA-Z0-9])"


def _compile_unified_pattern(aliases: List[str], case_sensitive: bool = False) -> Optional[Pattern[str]]:
    """Compiles aliases into a single regex with longest aliases first."""
    if not aliases:
        return None
    sorted_aliases = sorted(aliases, key=len, reverse=True)
    regex_parts = [_build_alias_regex(alias) for alias in sorted_aliases]
    flags = 0 if case_sensitive else re.IGNORECASE
    return re.compile("|".join(regex_parts), flags)


# Precompiled matchers cache
class CompiledSkillMatcher:
    def __init__(self, spec: SkillSpec):
        self.spec = spec
        self.general_ci_pattern = _compile_unified_pattern(spec.general_aliases, case_sensitive=False)
        self.general_cs_pattern = _compile_unified_pattern(spec.case_sensitive_aliases, case_sensitive=True)
        self.section_ci_pattern = _compile_unified_pattern(spec.section_only_aliases, case_sensitive=False)


COMPILED_MATCHERS: List[CompiledSkillMatcher] = [
    CompiledSkillMatcher(spec) for spec in TAXONOMY_SPECS
]


def _extract_skills_section_spans(text: str) -> List[Tuple[int, int]]:
    """Locates character start/end spans of explicit skills sections in the resume text."""
    spans = []
    for match in SKILLS_SECTION_PATTERN.finditer(text):
        spans.append(match.span(1))
    return spans


def _is_in_spans(match_span: Tuple[int, int], section_spans: List[Tuple[int, int]]) -> bool:
    """Checks whether a given match span falls within any identified section span."""
    m_start, m_end = match_span
    for s_start, s_end in section_spans:
        if m_start >= s_start and m_end <= s_end:
            return True
    return False


def _merge_spans(spans: List[Tuple[int, int]]) -> List[Tuple[int, int]]:
    """
    Merges overlapping match spans, keeping the longer match.
    Guarantees that sub-aliases (e.g. 'React' inside 'React.js') are not double-counted.
    """
    if not spans:
        return []
    sorted_spans = sorted(spans, key=lambda s: (s[0], -(s[1] - s[0])))
    non_overlapping: List[Tuple[int, int]] = []
    current_end = -1

    for start, end in sorted_spans:
        if start >= current_end:
            non_overlapping.append((start, end))
            current_end = end

    return non_overlapping


def _is_exact_canonical_match(canonical_name: str, text: str) -> bool:
    """
    Verifies whether the canonical skill name appears as a genuine,
    case-sensitive, whole-word token in the text (not a mere substring).
    """
    if re.fullmatch(r"[\w\s-]+", canonical_name):
        pattern = rf"\b{re.escape(canonical_name)}\b"
    else:
        pattern = rf"(?<![a-zA-Z0-9]){re.escape(canonical_name)}(?![a-zA-Z0-9])"
    return bool(re.search(pattern, text))


def calculate_confidence_score(
    total_occurrences: int,
    in_skills_section: bool,
    exact_canonical_match: bool
) -> float:
    """
    Computes a deterministic heuristic score between 0.0 and 100.0.

    Heuristic Breakdown:
    - Base score for a confirmed match: 80.0
    - Multi-mention recurrence boost: +5.0 per additional occurrence (max +10.0)
    - Presence in a dedicated skills section: +7.0
    - Exact whole-word case match to canonical name: +3.0

    Note: This is an explainable heuristic weight for ranking and screening,
    not a calibrated statistical or Bayesian probability.
    """
    base_score = 80.0
    frequency_boost = min((total_occurrences - 1) * 5.0, 10.0) if total_occurrences > 1 else 0.0
    section_boost = 7.0 if in_skills_section else 0.0
    canonical_boost = 3.0 if exact_canonical_match else 0.0

    score = base_score + frequency_boost + section_boost + canonical_boost
    return round(min(score, 100.0), 2)


def extract_skills(text: str) -> List[ExtractedSkill]:
    """
    Extracts normalized skills from parsed resume text.

    Args:
        text: Clean plain text of the resume.

    Returns:
        List of ExtractedSkill models ordered by category and confidence score descending.
    """
    if not text or not text.strip():
        return []

    section_spans = _extract_skills_section_spans(text)
    detected_skills: List[ExtractedSkill] = []

    for matcher in COMPILED_MATCHERS:
        spec = matcher.spec
        raw_spans: List[Tuple[int, int]] = []

        # 1. Match case-insensitive general aliases
        if matcher.general_ci_pattern:
            for match in matcher.general_ci_pattern.finditer(text):
                raw_spans.append(match.span())

        # 2. Match strict case-sensitive aliases (e.g. \bJS\b, \bTS\b)
        if matcher.general_cs_pattern:
            for match in matcher.general_cs_pattern.finditer(text):
                raw_spans.append(match.span())

        # 3. Match section-only aliases strictly within skills sections (e.g. 'react', 'node', 'vue')
        if section_spans and matcher.section_ci_pattern:
            for s_start, s_end in section_spans:
                section_text = text[s_start:s_end]
                for match in matcher.section_ci_pattern.finditer(section_text):
                    raw_spans.append((s_start + match.start(), s_start + match.end()))

        # Deduplicate overlapping spans (longest match wins)
        merged_spans = _merge_spans(raw_spans)
        if not merged_spans:
            continue

        total_occurrences = len(merged_spans)

        # Check if any genuine occurrence falls inside a dedicated skills section
        in_skills_section = any(_is_in_spans(span, section_spans) for span in merged_spans)

        # Check exact whole-word canonical match
        exact_canonical = _is_exact_canonical_match(spec.canonical_name, text)

        confidence = calculate_confidence_score(
            total_occurrences=total_occurrences,
            in_skills_section=in_skills_section,
            exact_canonical_match=exact_canonical
        )

        detected_skills.append(
            ExtractedSkill(
                skill_name=spec.canonical_name,
                category=spec.category,
                confidence_score=confidence
            )
        )

    # Sort deterministically: by category, then confidence score descending, then skill name
    detected_skills.sort(key=lambda s: (s.category or "", -(s.confidence_score or 0.0), s.skill_name))

    return detected_skills
