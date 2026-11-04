# Intelligent Resume Screening and Candidate Ranking System

An AI-driven recruitment and talent screening platform that parses resumes, extracts candidate skills, computes semantic fit against job descriptions, and generates ranked candidate leaderboards.

---

## Architecture Overview

The system is organized into a modular four-tier architecture with strict boundary separation:

```
[ Frontend: HTML / CSS / JS ]
               │
          HTTP │ (REST API)
               ▼
[ Backend: Java Spring Boot (Port 8080) ] ◄─── JDBC ───► [ MySQL Database: resume_screening_db ]
               │
          HTTP │ (REST API)
               ▼
[ AI Service: Python FastAPI (Port 8000) ]
```

### Key Architectural Boundaries
1. **Frontend**: Pure HTML, CSS, and Vanilla JavaScript user interface. Communicates exclusively with the Java backend.
2. **Backend (Java Spring Boot)**: Core business orchestrator and API gateway. Manages candidate records, job postings, screening results, and file storage. **Only the Java backend interacts with MySQL.**
3. **Database (MySQL)**: Persistent relational store for job descriptions, candidate metadata, parsed text, extracted skills, screening scores, and rankings.
4. **AI Microservice (Python FastAPI)**: Stateless computation engine performing NLP-driven document parsing (PDF, DOCX, TXT), rule-based skill extraction, TF-IDF cosine similarity, experience matching, and candidate ranking. **Python does not access MySQL directly.**

---

## Technology Stack

| Layer | Technologies & Frameworks | Details |
| :--- | :--- | :--- |
| **Frontend** | HTML5, CSS3, JavaScript | Responsive client interface |
| **Backend** | Java 17, Spring Boot 3.2.5, Maven | Spring Web, Spring Data JPA, Hibernate, Bean Validation |
| **Database** | MySQL 8.0+ | Relational schema with foreign keys and composite indexes |
| **AI Microservice** | Python 3.x, FastAPI 0.115.6, Uvicorn 0.34.0 | Pydantic 2.10.4, PyPDF 5.1.0, python-docx 1.1.2, Scikit-learn 1.6.0, NumPy 2.2.1 |

---

## Project Folder Structure

```
Intelligent-Resume-Screening-System/
├── README.md                           # Project documentation
│
├── ai-service-python/                  # Python FastAPI AI Microservice (Port 8000)
│   ├── main.py                         # FastAPI entrypoint, routes, and CORS setup
│   ├── requirements.txt                # Pinned Python package dependencies
│   ├── models/
│   │   └── schemas.py                  # Pydantic request and response contracts
│   └── services/
│       ├── parser.py                   # PDF, DOCX, and TXT document text extraction
│       ├── extractor.py                # Rule-based skill taxonomy and scoring engine
│       └── matcher.py                  # TF-IDF cosine similarity, experience match & ranker
│
├── backend-java/                       # Java Spring Boot REST API (Port 8080)
│   ├── pom.xml                         # Maven dependencies & build configuration
│   └── src/main/
│       ├── java/com/system/resume/     # Java application source code
│       │   ├── ResumeScreeningApplication.java
│       │   ├── client/                 # HTTP client for Python AI service
│       │   ├── controller/             # REST API controllers
│       │   ├── dto/                    # Data Transfer Objects
│       │   ├── model/                  # JPA entity models
│       │   ├── repository/             # Spring Data JPA repositories
│       │   └── service/                # Business logic services
│       └── resources/
│           ├── application.properties  # Server, database, and AI service configuration
│           └── schema.sql              # MySQL DDL table creation script
│
└── frontend/                           # Client User Interface
    ├── index.html                      # Single-page application entrypoint
    ├── css/
    │   ├── styles.css                  # Layout, variables, and typography
    │   └── components.css              # Cards, badges, buttons, and tables
    ├── js/
    │   ├── api.js                      # REST client communicating with Spring Boot
    │   └── app.js                      # DOM events, state management, and UI rendering
    └── assets/                         # Static images and icons
```

---

## Database Schema (`resume_screening_db`)

The database schema is defined in `backend-java/src/main/resources/schema.sql` and consists of 6 relational tables:

1. `job_descriptions`: Job postings, required skills (JSON), and minimum experience.
2. `candidates`: Candidate identity (name, unique email, phone).
3. `resumes`: Uploaded resume tracking, file paths, parsed clean text, and processing status.
4. `resume_skills`: Granular extracted skills with category and confidence scores.
5. `screening_results`: AI match scores (overall, skills, experience), matched/missing skills (JSON), and narrative summaries.
6. `candidate_rankings`: Final candidate leaderboard and shortlist review status for each job.

---

## AI Service REST API Endpoints

The Python microservice exposes the following endpoints on `http://localhost:8000`:

* `GET /health`: Liveness and service status check.
* `POST /api/v1/parse-document`: Accepts binary file uploads (PDF, DOCX, TXT; 10 MB limit), extracts clean text and structured skills.
* `POST /api/v1/parse-text`: Accepts raw text strings for cleaning and skill extraction.
* `POST /api/v1/match`: Compares a candidate resume against a job description, returning composite match, skill, and experience scores.
* `POST /api/v1/rank`: Screens and deterministically ranks a batch of candidates for a target position.

---

## Environment Setup & Running

### 1. Database Setup
1. Ensure MySQL 8.0+ is running locally.
2. Create and initialize the database schema:
   ```bash
   mysql -u root -p < backend-java/src/main/resources/schema.sql
   ```

### 2. AI Microservice Setup (Python)
1. Navigate to `ai-service-python/`:
   ```bash
   cd ai-service-python
   python -m venv venv
   # Windows:
   venv\Scripts\activate
   # Linux/macOS:
   source venv/bin/activate
   ```
2. Install dependencies:
   ```bash
   pip install -r requirements.txt
   ```
3. Start the service:
   ```bash
   uvicorn main:app --host 0.0.0.0 --port 8000 --reload
   ```
   Interactive OpenAPI documentation is available at `http://localhost:8000/docs`.

### 3. Backend Setup (Java Spring Boot)
1. Navigate to `backend-java/`:
   ```bash
   cd backend-java
   ```
2. Configure database credentials in `src/main/resources/application.properties` or set environment variables:
   ```bash
   set DB_USERNAME=root
   set DB_PASSWORD=your_password
   set AI_SERVICE_URL=http://localhost:8000
   ```
3. Build and launch:
   ```bash
   mvn clean spring-boot:run
   ```
   The backend runs on `http://localhost:8080`.

### 4. Frontend Setup
Open `frontend/index.html` in a web browser or serve it via a local static HTTP server.
