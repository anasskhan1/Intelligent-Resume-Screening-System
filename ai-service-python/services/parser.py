"""
Resume Document and Text Parser Service.
Extracts clean plain text from PDF, DOCX, and TXT resumes.
"""

import io
import os
import re
import zipfile
from pathlib import Path
from typing import BinaryIO, Optional, Tuple, Union

import docx
from docx.opc.exceptions import PackageNotFoundError
import pypdf
from pypdf.errors import FileNotDecryptedError, PdfReadError


# Supported resume file extensions
SUPPORTED_EXTENSIONS = {".pdf", ".docx", ".txt"}


def clean_extracted_text(text: str) -> str:
    """
    Cleans and normalizes extracted resume text.
    - Eliminates null bytes and non-breaking spaces
    - Normalizes line breaks (\r\n and \r to \n)
    - Collapses redundant horizontal whitespace
    - Preserves paragraph separation while trimming excess blank lines
    """
    if not text:
        return ""

    # Replace null bytes and non-breaking spaces
    cleaned = text.replace("\x00", "").replace("\u00a0", " ")

    # Normalize line breaks
    cleaned = re.sub(r"\r\n|\r", "\n", cleaned)

    # Normalize horizontal whitespace (tabs and consecutive spaces)
    cleaned = re.sub(r"[ \t]+", " ", cleaned)

    # Clean leading/trailing spaces on each line
    lines = [line.strip() for line in cleaned.split("\n")]

    # Rejoin lines, collapsing 3+ consecutive newlines to 2
    cleaned = "\n".join(lines)
    cleaned = re.sub(r"\n{3,}", "\n\n", cleaned)

    return cleaned.strip()


def extract_text_from_pdf(source: Union[bytes, BinaryIO, str, Path]) -> str:
    """
    Extracts text from a PDF source using pypdf.
    Handles encrypted PDFs and per-page extraction errors gracefully without crashing.
    """
    stream: Union[BinaryIO, str, Path]
    if isinstance(source, bytes):
        stream = io.BytesIO(source)
    else:
        stream = source

    try:
        reader = pypdf.PdfReader(stream)

        # Handle encrypted / password-protected PDF
        if reader.is_encrypted:
            decrypted = False
            try:
                # Attempt empty password decryption (handles standard read-permission restrictions)
                decrypted = bool(reader.decrypt(""))
            except Exception:
                decrypted = False

            if not decrypted or reader.is_encrypted:
                raise ValueError("The PDF document is password-protected or encrypted and cannot be processed.")

        if not reader.pages:
            raise ValueError("The PDF document contains no pages.")

        extracted_pages = []
        page_errors = 0

        # Read pages with individual error handling to prevent single-page corruption from failing the file
        for page_num, page in enumerate(reader.pages, start=1):
            try:
                page_text = page.extract_text()
                if page_text and page_text.strip():
                    extracted_pages.append(page_text.strip())
            except Exception:
                page_errors += 1
                continue

        raw_text = "\n\n".join(extracted_pages)
        cleaned = clean_extracted_text(raw_text)

        if not cleaned:
            if page_errors > 0 and page_errors == len(reader.pages):
                raise ValueError(f"Failed to read any content from the {page_errors} page(s) in the PDF document.")
            raise ValueError(
                "No selectable text found in the PDF. Ensure the file contains text and is not an image-only scan."
            )

        return cleaned

    except FileNotDecryptedError as e:
        raise ValueError("The PDF document is password-protected or encrypted and cannot be processed.") from e
    except PdfReadError as e:
        raise ValueError(f"Corrupted or unreadable PDF document: {str(e)}") from e


def extract_text_from_docx(source: Union[bytes, BinaryIO, str, Path]) -> str:
    """
    Extracts text from a Word DOCX source using python-docx.
    Extracts content from body paragraphs and table cells.
    """
    stream: Union[BinaryIO, str, Path]
    if isinstance(source, bytes):
        stream = io.BytesIO(source)
    else:
        stream = source

    try:
        doc = docx.Document(stream)
        text_elements = []

        # Extract text from paragraphs
        for paragraph in doc.paragraphs:
            if paragraph.text and paragraph.text.strip():
                text_elements.append(paragraph.text.strip())

        # Extract text from tables (common in resume layouts)
        for table in doc.tables:
            for row in table.rows:
                row_cells = [cell.text.strip() for cell in row.cells if cell.text and cell.text.strip()]
                if row_cells:
                    text_elements.append(" | ".join(row_cells))

        raw_text = "\n".join(text_elements)
        return clean_extracted_text(raw_text)

    except PackageNotFoundError as e:
        raise ValueError("Invalid or corrupted DOCX document structure.") from e
    except Exception as e:
        raise ValueError(f"Failed to read DOCX document: {str(e)}") from e


def extract_text_from_txt(source: Union[bytes, BinaryIO, str, Path]) -> str:
    """
    Extracts and decodes text from a plain text source (UTF-8 with Latin-1 fallback).
    """
    if isinstance(source, bytes):
        data = source
    elif isinstance(source, (str, Path)):
        with open(source, "rb") as f:
            data = f.read()
    else:
        data = source.read()

    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        text = data.decode("latin-1", errors="replace")

    return clean_extracted_text(text)


def parse_resume_document(
    file_bytes: bytes,
    filename: str
) -> Tuple[bool, str, Optional[str]]:
    """
    Validates and extracts clean text from resume binary data.

    Returns:
        (success: bool, clean_text: str, error_message: Optional[str])
    """
    if not filename or not filename.strip():
        return False, "", "Filename must not be empty."

    # Validate file size
    if not file_bytes or len(file_bytes) == 0:
        return False, "", f"File '{filename}' is empty (0 bytes)."

    # Validate extension
    _, ext = os.path.splitext(filename.lower())
    if ext not in SUPPORTED_EXTENSIONS:
        supported_list = ", ".join(sorted(SUPPORTED_EXTENSIONS))
        return False, "", f"Unsupported file type '{ext}'. Supported formats: {supported_list}."

    # Validate file content signature (magic bytes) against extension
    if ext == ".pdf":
        if not file_bytes.startswith(b"%PDF"):
            return False, "", f"File '{filename}' content does not match extension '.pdf'. Missing valid PDF signature (%PDF)."
    if ext == ".docx":
        if not file_bytes.startswith(b"PK\x03\x04"):
            return False, "", f"File '{filename}' content does not match extension '.docx'. Missing valid OpenXML/ZIP archive signature (PK..)."
        try:
            with zipfile.ZipFile(io.BytesIO(file_bytes)) as zf:
                namelist = zf.namelist()
                if not any(name == "word/document.xml" or name.startswith("word/") for name in namelist):
                    return False, "", f"File '{filename}' content does not match extension '.docx'. ZIP archive does not contain an OOXML Word document (missing 'word/document.xml')."
        except Exception:
            return False, "", f"File '{filename}' is not a valid DOCX/ZIP document package."
    if ext == ".txt":
        if file_bytes.startswith(b"%PDF") or file_bytes.startswith(b"PK\x03\x04") or file_bytes.startswith(b"MZ") or file_bytes.startswith(b"\x7fELF"):
            return False, "", f"File '{filename}' content does not match extension '.txt'. Binary/executable signature detected."
        sample = file_bytes[:1024]
        if b"\x00" in sample:
            return False, "", f"File '{filename}' content does not match extension '.txt'. Binary null bytes detected."
        for byte_val in sample:
            if (byte_val < 0x20 and byte_val not in (0x09, 0x0A, 0x0D)) or byte_val == 0x7F:
                return False, "", f"File '{filename}' content does not match extension '.txt'. Binary control characters detected."

    try:
        if ext == ".pdf":
            extracted_text = extract_text_from_pdf(file_bytes)
        elif ext == ".docx":
            extracted_text = extract_text_from_docx(file_bytes)
        elif ext == ".txt":
            extracted_text = extract_text_from_txt(file_bytes)
        else:
            return False, "", f"Unsupported extension: {ext}"

        if not extracted_text:
            return (
                False,
                "",
                f"No selectable text found in '{filename}'. Ensure the file is not an image-only scan."
            )

        return True, extracted_text, None

    except ValueError as ve:
        return False, "", str(ve)
    except Exception as exc:
        return False, "", f"Unexpected error parsing '{filename}': {str(exc)}"


def parse_plain_text(
    text: str,
    source_name: Optional[str] = "plain_text.txt"
) -> Tuple[bool, str, Optional[str]]:
    """
    Validates and cleans directly submitted plain text.

    Returns:
        (success: bool, clean_text: str, error_message: Optional[str])
    """
    if not text or not text.strip():
        return False, "", f"Text content for '{source_name}' cannot be empty."

    cleaned = clean_extracted_text(text)
    if not cleaned:
        return False, "", f"Text content for '{source_name}' contains no readable characters."

    return True, cleaned, None
