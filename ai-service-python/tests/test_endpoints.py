"""
Tests for FastAPI endpoint definitions, synchronous thread-pool execution,
and schema compatibility with the Java Spring Boot backend.
"""

import inspect
import io
import unittest
import zipfile
from starlette.datastructures import UploadFile

from main import (
    health_check,
    match_resume,
    parse_document,
    parse_text,
    rank_candidate_pool,
)
from models.schemas import (
    BatchRankRequest,
    CandidateProfile,
    MatchRequest,
    ParseTextRequest,
)


class TestEndpointsResilienceAndThreadSafety(unittest.TestCase):

    def test_cpu_bound_endpoints_are_synchronous(self):
        """
        Verify that CPU-bound parsing and matching endpoints are defined as standard
        synchronous functions (not coroutine functions).
        This guarantees FastAPI executes them in its external worker thread pool
        rather than blocking the asyncio event loop.
        """
        self.assertFalse(
            inspect.iscoroutinefunction(parse_document),
            "parse_document must be a standard synchronous def to prevent blocking event loop"
        )
        self.assertFalse(
            inspect.iscoroutinefunction(parse_text),
            "parse_text must be a standard synchronous def to prevent blocking event loop"
        )
        self.assertFalse(
            inspect.iscoroutinefunction(match_resume),
            "match_resume must be a standard synchronous def to prevent blocking event loop"
        )
        self.assertFalse(
            inspect.iscoroutinefunction(rank_candidate_pool),
            "rank_candidate_pool must be a standard synchronous def to prevent blocking event loop"
        )

    def test_parse_text_synchronous_execution(self):
        """Test that parse_text executes cleanly in synchronous thread context."""
        sample_text = (
            "Alice Smith\n"
            "Email: alice@example.com\n"
            "Phone: 555-123-4567\n"
            "Summary: Senior Software Engineer with 6 years experience in Java, Spring Boot, and Docker."
        )
        req = ParseTextRequest(text=sample_text, file_name="alice.txt")
        resp = parse_text(req)

        self.assertTrue(resp.success)
        self.assertEqual(resp.file_name, "alice.txt")
        self.assertIn("alice@example.com", resp.candidate_email)
        self.assertGreater(resp.word_count, 0)
        skill_names = [s.skill_name.lower() for s in resp.extracted_skills]
        self.assertIn("java", skill_names)
        self.assertIn("docker", skill_names)

    def test_parse_document_synchronous_uploadfile(self):
        """Test that parse_document handles UploadFile synchronously via .file.read."""
        content = b"John Doe\nEmail: john@example.com\nExperienced Python and FastAPI developer."
        upload = UploadFile(filename="john.txt", file=io.BytesIO(content))

        resp = parse_document(upload)
        self.assertTrue(resp.success)
        self.assertEqual(resp.file_name, "john.txt")
        self.assertIn("john@example.com", resp.candidate_email)
        self.assertIn("Python", resp.parsed_text)

    def test_parse_document_empty_file_handling(self):
        """Test that 0-byte files return clean error response without crashing."""
        empty_upload = UploadFile(filename="empty.txt", file=io.BytesIO(b""))
        resp = parse_document(empty_upload)
        self.assertFalse(resp.success)
        self.assertIn("empty", resp.error_message.lower())

    def test_parse_document_pdf_content_mismatch(self):
        """Test that PDF files without %PDF magic bytes are rejected."""
        fake_pdf = UploadFile(filename="fake.pdf", file=io.BytesIO(b"Just plain text, not a PDF"))
        resp = parse_document(fake_pdf)
        self.assertFalse(resp.success)
        self.assertIn("Missing valid PDF signature (%PDF)", resp.error_message)

    def test_parse_document_docx_content_mismatch(self):
        """Test that DOCX files without PK.. ZIP magic bytes are rejected."""
        fake_docx = UploadFile(filename="fake.docx", file=io.BytesIO(b"%PDF-1.4\nDisguised PDF"))
        resp = parse_document(fake_docx)
        self.assertFalse(resp.success)
        self.assertIn("Missing valid OpenXML/ZIP archive signature", resp.error_message)

    def test_parse_document_docx_non_word_zip(self):
        """Test that arbitrary ZIP archives without word/document.xml are rejected as DOCX."""
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as zf:
            zf.writestr("archive.txt", "Some text in a generic zip archive")
        non_docx = UploadFile(filename="archive.docx", file=io.BytesIO(buf.getvalue()))
        resp = parse_document(non_docx)
        self.assertFalse(resp.success)
        self.assertIn("ZIP archive does not contain an OOXML Word document", resp.error_message)

    def test_parse_document_txt_binary_mismatch(self):
        """Test that TXT files containing binary executable signatures are rejected."""
        fake_txt = UploadFile(filename="malware.txt", file=io.BytesIO(b"MZ\x00\x01\x02"))
        resp = parse_document(fake_txt)
        self.assertFalse(resp.success)
        self.assertIn("Binary/executable signature detected", resp.error_message)

    def test_parse_document_txt_control_characters(self):
        """Test that TXT files containing binary control characters are rejected."""
        control_txt = UploadFile(filename="control.txt", file=io.BytesIO(b"Hello\x07World"))
        resp = parse_document(control_txt)
        self.assertFalse(resp.success)
        self.assertIn("Binary control characters detected", resp.error_message)

    def test_match_resume_synchronous_execution(self):
        """Test that match_resume computes semantic score and skills match/gap."""
        req = MatchRequest(
            job_id=1,
            job_title="Java Developer",
            job_description="Seeking a Java developer with Spring Boot and AWS.",
            required_skills=["Java", "Spring Boot", "AWS"],
            min_experience_years=3,
            resume_id=10,
            candidate_name="Alice Smith",
            resume_text="Experienced in Java and Spring Boot with 5 years experience.",
            candidate_skills=["Java", "Spring Boot"]
        )
        resp = match_resume(req)

        self.assertEqual(resp.job_id, 1)
        self.assertEqual(resp.resume_id, 10)
        self.assertGreater(resp.match_score, 0.0)
        self.assertIn("Java", resp.matched_skills)
        self.assertIn("AWS", resp.missing_skills)

    def test_rank_candidate_pool_synchronous_execution(self):
        """Test that rank_candidate_pool ranks candidates deterministically."""
        candidates = [
            CandidateProfile(
                candidate_id=101,
                resume_id=201,
                candidate_name="Alice",
                resume_text="Senior Java Engineer with 7 years experience in Spring Boot and AWS.",
                candidate_skills=["Java", "Spring Boot", "AWS"]
            ),
            CandidateProfile(
                candidate_id=102,
                resume_id=202,
                candidate_name="Bob",
                resume_text="Junior developer with HTML and CSS.",
                candidate_skills=["HTML", "CSS"]
            ),
        ]
        req = BatchRankRequest(
            job_id=1,
            job_title="Senior Java Developer",
            job_description="Requires Java, Spring Boot, and AWS.",
            required_skills=["Java", "Spring Boot", "AWS"],
            min_experience_years=5,
            candidates=candidates
        )
        resp = rank_candidate_pool(req)

        self.assertEqual(len(resp.rankings), 2)
        # Alice should rank #1 due to strong skill match and experience
        self.assertEqual(resp.rankings[0].candidate_name, "Alice")
        self.assertEqual(resp.rankings[0].rank_position, 1)

    def test_verify_service_api_key_authentication(self):
        """Test service-to-service authentication security."""
        from fastapi import HTTPException
        import main

        original_key = main.AI_SERVICE_API_KEY
        try:
            # 1. Unset key allows all requests
            main.AI_SERVICE_API_KEY = ""
            main.verify_service_api_key(None, None)

            # 2. Configured key rejects missing credentials
            main.AI_SERVICE_API_KEY = "test-secret-key-12345"
            with self.assertRaises(HTTPException) as ctx:
                main.verify_service_api_key(None, None)
            self.assertEqual(ctx.exception.status_code, 401)

            # 3. Configured key rejects invalid credentials
            with self.assertRaises(HTTPException) as ctx:
                main.verify_service_api_key("wrong-key", None)
            self.assertEqual(ctx.exception.status_code, 401)

            # 4. Configured key accepts valid X-API-Key header
            main.verify_service_api_key("test-secret-key-12345", None)

            # 5. Configured key accepts valid Authorization Bearer token
            main.verify_service_api_key(None, "Bearer test-secret-key-12345")
        finally:
            main.AI_SERVICE_API_KEY = original_key


if __name__ == "__main__":
    unittest.main()
