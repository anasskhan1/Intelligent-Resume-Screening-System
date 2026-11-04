"""
Unit tests for Intelligent Candidate Contact Extractor Service.
Tests candidate name, email, and phone number extraction across synthetic and edge-case resumes.
"""

import unittest
import sys
from pathlib import Path

# Add ai-service-python directory to path
BASE_DIR = Path(__file__).resolve().parent.parent
if str(BASE_DIR) not in sys.path:
    sys.path.insert(0, str(BASE_DIR))

from services.contact_extractor import (
    extract_candidate_name,
    extract_candidate_email,
    extract_candidate_phone,
    extract_contact_info
)


class TestContactExtractor(unittest.TestCase):

    def test_case_1_indian_mobile_and_name(self):
        """Case 1: Clearly stated name, email, and Indian mobile number."""
        text = """Rahul Sharma
rahul.sharma@example.com | +91 98765 43210
Bangalore, India

Summary:
Experienced Java and Spring Boot engineer with Docker and AWS experience.
"""
        info = extract_contact_info(text)
        self.assertEqual(info["candidate_name"], "Rahul Sharma")
        self.assertEqual(info["candidate_email"], "rahul.sharma@example.com")
        self.assertEqual(info["candidate_phone"], "+91 98765 43210")

    def test_case_2_international_phone(self):
        """Case 2: International US phone number and standard name format."""
        text = """John Smith
Email: john.smith@domain.com
Phone: +1 (555) 234-5678
New York, NY

Summary:
Cloud Architect with AWS and Kubernetes expertise.
"""
        info = extract_contact_info(text)
        self.assertEqual(info["candidate_name"], "John Smith")
        self.assertEqual(info["candidate_email"], "john.smith@domain.com")
        self.assertEqual(info["candidate_phone"], "+1 (555) 234-5678")

    def test_case_3_missing_name_valid_email(self):
        """Case 3: Missing name (only CV heading & job title), valid email and phone."""
        text = """CURRICULUM VITAE
SOFTWARE ENGINEER
contact@applicant.net
+91-9123456789

Summary:
Full stack developer with TypeScript and React.
"""
        info = extract_contact_info(text)
        self.assertIsNone(info["candidate_name"])
        self.assertEqual(info["candidate_email"], "contact@applicant.net")
        self.assertEqual(info["candidate_phone"], "+91-9123456789")

    def test_case_4_missing_phone(self):
        """Case 4: Clearly stated name and email, missing phone number."""
        text = """Aarav Patel
aarav.patel@techmail.com
Software Developer

Technical Skills:
Java, Python, Docker
"""
        info = extract_contact_info(text)
        self.assertEqual(info["candidate_name"], "Aarav Patel")
        self.assertEqual(info["candidate_email"], "aarav.patel@techmail.com")
        self.assertIsNone(info["candidate_phone"])

    def test_case_5_generic_filename_content(self):
        """Case 5: Content has real candidate name when filename is generic (25797445.pdf)."""
        text = """Priya Sundaram
priya.sundaram@gmail.com
+91 9988776655
Hyderabad, India

Summary:
Senior Backend Developer with Python, MySQL, and Kubernetes.
"""
        info = extract_contact_info(text)
        self.assertEqual(info["candidate_name"], "Priya Sundaram")
        self.assertEqual(info["candidate_email"], "priya.sundaram@gmail.com")
        self.assertEqual(info["candidate_phone"], "+91 9988776655")

    def test_case_6_multiple_emails_and_phones(self):
        """Case 6: Candidate personal contact vs corporate employer contact."""
        text = """Amitabh Verma
personal.amitabh@gmail.com | +91 98765 11223
Bangalore, India

Experience:
ABC Tech Solutions (2020 - 2024)
Company Contact: hr@abctech.com, Phone: 022 23456789
"""
        info = extract_contact_info(text)
        self.assertEqual(info["candidate_name"], "Amitabh Verma")
        self.assertEqual(info["candidate_email"], "personal.amitabh@gmail.com")
        self.assertEqual(info["candidate_phone"], "+91 98765 11223")

    def test_case_7_job_titles_before_name(self):
        """Case 7: Job titles and CV headings that must NOT be mistaken for candidate name."""
        text = """CURRICULUM VITAE
FULL STACK JAVA DEVELOPER
SENIOR SOFTWARE ENGINEER
Vikram Malhotra
vikram.m@domain.in
+91 9811223344
"""
        info = extract_contact_info(text)
        self.assertEqual(info["candidate_name"], "Vikram Malhotra")
        self.assertEqual(info["candidate_email"], "vikram.m@domain.in")
        self.assertEqual(info["candidate_phone"], "+91 9811223344")

    def test_edge_case_hyphenated_and_prefix_names(self):
        """Edge Case: Hyphenated names and academic/salutation prefixes."""
        text_fr = """Jean-Pierre Dupont
jean.dupont@corp.fr
+33 1 42 68 55 00
Paris, France
"""
        info_fr = extract_contact_info(text_fr)
        self.assertEqual(info_fr["candidate_name"], "Jean-Pierre Dupont")
        self.assertEqual(info_fr["candidate_email"], "jean.dupont@corp.fr")
        self.assertEqual(info_fr["candidate_phone"], "+33 1 42 68 55 00")

        text_prefix = """Dr. Maria Garcia-Lopez
maria.gl@domain.es
+34 91 123 45 67
Madrid, Spain
"""
        info_prefix = extract_contact_info(text_prefix)
        self.assertEqual(info_prefix["candidate_name"], "Maria Garcia-Lopez")
        self.assertEqual(info_prefix["candidate_email"], "maria.gl@domain.es")
        self.assertEqual(info_prefix["candidate_phone"], "+34 91 123 45 67")

    def test_edge_case_dates_and_pincodes_not_phones(self):
        """Edge Case: Dates, PIN codes, and GPAs must not be misidentified as phone numbers."""
        text = """Aarav K. Patel
aarav.patel@research.org
Education:
IIT Bombay (2018 - 2022)
GPA: 9.2/10
PIN: 400076
"""
        info = extract_contact_info(text)
        self.assertEqual(info["candidate_name"], "Aarav K Patel")
        self.assertEqual(info["candidate_email"], "aarav.patel@research.org")
        self.assertIsNone(info["candidate_phone"])


if __name__ == "__main__":
    unittest.main()

