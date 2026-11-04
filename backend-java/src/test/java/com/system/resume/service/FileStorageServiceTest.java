package com.system.resume.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FileStorageServiceTest {

    private Path tempDir;
    private FileStorageService fileStorageService;

    @BeforeEach
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("file_storage_test_");
        fileStorageService = new FileStorageService(tempDir.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDir != null && Files.exists(tempDir)) {
            Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }

    @Test
    @DisplayName("Valid PDF with %PDF signature stores successfully")
    void testStoreFile_ValidPdf_Success() {
        byte[] pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\ntrailer\n<<>>\n%%EOF".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.pdf", "application/pdf", pdfBytes
        );

        String storedPath = fileStorageService.storeFile(file);
        assertNotNull(storedPath);
        assertTrue(new File(storedPath).exists());
        assertTrue(storedPath.endsWith("resume.pdf"));
    }

    private byte[] createMinimalDocxBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("word/document.xml");
            zos.putNextEntry(entry);
            zos.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:t>Resume</w:t></w:r></w:p></w:body></w:document>".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    private byte[] createNonDocxZipBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("unrelated.txt");
            zos.putNextEntry(entry);
            zos.write("Just an arbitrary zip archive without word/document.xml".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    @DisplayName("Valid DOCX with PK.. zip header and OOXML structure stores successfully")
    void testStoreFile_ValidDocx_Success() throws IOException {
        byte[] docxBytes = createMinimalDocxBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", docxBytes
        );

        String storedPath = fileStorageService.storeFile(file);
        assertNotNull(storedPath);
        assertTrue(new File(storedPath).exists());
        assertTrue(storedPath.endsWith("resume.docx"));
    }

    @Test
    @DisplayName("Reject DOCX when ZIP archive lacks word/document.xml OOXML entry")
    void testStoreFile_DocxWithoutWordDocument_ThrowsException() throws IOException {
        byte[] nonDocxZipBytes = createNonDocxZipBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "archive.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", nonDocxZipBytes
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fileStorageService.storeFile(file)
        );
        assertTrue(ex.getMessage().contains("ZIP archive does not contain an OOXML Word document"));
    }

    @Test
    @DisplayName("Valid TXT with plain text content stores successfully")
    void testStoreFile_ValidTxt_Success() {
        byte[] txtBytes = "Alice Smith\nSoftware Engineer with Java experience.\nEmail: alice@example.com".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "resume.txt", "text/plain", txtBytes
        );

        String storedPath = fileStorageService.storeFile(file);
        assertNotNull(storedPath);
        assertTrue(new File(storedPath).exists());
        assertTrue(storedPath.endsWith("resume.txt"));
    }

    @Test
    @DisplayName("Reject PDF when file content lacks %PDF signature")
    void testStoreFile_PdfContentMismatch_ThrowsException() {
        byte[] fakePdfBytes = "This is not a real PDF file, just plain text.".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.pdf", "application/pdf", fakePdfBytes
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fileStorageService.storeFile(file)
        );
        assertTrue(ex.getMessage().contains("Missing valid PDF signature (%PDF)"));
    }

    @Test
    @DisplayName("Reject DOCX when file content lacks PK.. ZIP header")
    void testStoreFile_DocxContentMismatch_ThrowsException() {
        byte[] fakeDocxBytes = "%PDF-1.4\nDisguised PDF claiming to be DOCX".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", fakeDocxBytes
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fileStorageService.storeFile(file)
        );
        assertTrue(ex.getMessage().contains("Missing valid OpenXML/ZIP archive signature (PK..)"));
    }

    @Test
    @DisplayName("Reject TXT when disguised executable header MZ is detected")
    void testStoreFile_TxtContainingExecutableHeader_ThrowsException() {
        byte[] exeBytes = new byte[]{0x4D, 0x5A, 0x00, 0x01, 0x02}; // Windows PE header
        MockMultipartFile file = new MockMultipartFile(
                "file", "malware.txt", "text/plain", exeBytes
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fileStorageService.storeFile(file)
        );
        assertTrue(ex.getMessage().contains("Executable binary signature detected"));
    }

    @Test
    @DisplayName("Reject TXT when null bytes are detected")
    void testStoreFile_TxtContainingNullBytes_ThrowsException() {
        byte[] binaryBytes = new byte[]{0x41, 0x42, 0x00, 0x43, 0x44}; // Contains null byte
        MockMultipartFile file = new MockMultipartFile(
                "file", "binary.txt", "text/plain", binaryBytes
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fileStorageService.storeFile(file)
        );
        assertTrue(ex.getMessage().contains("Binary null bytes detected"));
    }

    @Test
    @DisplayName("Reject TXT when binary control characters are detected")
    void testStoreFile_TxtContainingBinaryControlCharacters_ThrowsException() {
        byte[] controlBytes = new byte[]{0x41, 0x42, 0x07, 0x43, 0x44}; // 0x07 is bell/control character
        MockMultipartFile file = new MockMultipartFile(
                "file", "control.txt", "text/plain", controlBytes
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fileStorageService.storeFile(file)
        );
        assertTrue(ex.getMessage().contains("Binary control characters detected"));
    }

    @Test
    @DisplayName("Reject empty 0-byte file")
    void testStoreFile_EmptyFile_ThrowsException() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fileStorageService.storeFile(file)
        );
        assertTrue(ex.getMessage().contains("Cannot store an empty or null file"));
    }

    @Test
    @DisplayName("Reject truncated file smaller than minimum signature length")
    void testStoreFile_TruncatedPdf_ThrowsException() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "short.pdf", "application/pdf", new byte[]{0x25, 0x50} // Only 2 bytes
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fileStorageService.storeFile(file)
        );
        assertTrue(ex.getMessage().contains("Missing valid PDF signature"));
    }

    @Test
    @DisplayName("Reject path traversal attempt in filename")
    void testStoreFile_PathTraversal_ThrowsException() {
        byte[] pdfBytes = "%PDF-1.4\ncontent".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../traversal.pdf", "application/pdf", pdfBytes
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fileStorageService.storeFile(file)
        );
        assertTrue(ex.getMessage().contains("invalid path sequence"));
    }

    @Test
    @DisplayName("Reject unsupported file extension")
    void testStoreFile_UnsupportedExtension_ThrowsException() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "image.png", "image/png", new byte[]{1, 2, 3, 4}
        );

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                fileStorageService.storeFile(file)
        );
        assertTrue(ex.getMessage().contains("Unsupported file type '.png'"));
    }

    @Test
    @DisplayName("computeSha256 produces valid 64-character lowercase hex digest")
    void testComputeSha256() {
        byte[] data = "Hello World".getBytes();
        String hash = fileStorageService.computeSha256(data);

        assertNotNull(hash);
        assertEquals(64, hash.length());
        assertEquals("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e", hash);
    }

    @Test
    @DisplayName("deleteFile successfully deletes file and handles non-existent or blank paths safely")
    void testDeleteFile() throws IOException {
        Path testFile = Files.createTempFile(tempDir, "test_del_", ".pdf");
        assertTrue(Files.exists(testFile));

        fileStorageService.deleteFile(testFile.toString());
        assertFalse(Files.exists(testFile));

        // Safe handling of null, blank, and already-deleted paths
        assertDoesNotThrow(() -> fileStorageService.deleteFile(null));
        assertDoesNotThrow(() -> fileStorageService.deleteFile("   "));
        assertDoesNotThrow(() -> fileStorageService.deleteFile(testFile.toString()));
    }
}

