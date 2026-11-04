package com.system.resume.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CandidateExportServiceTest {

    @Test
    @DisplayName("Should neutralize CSV formula injection characters by prepending single quote")
    void testFormulaInjectionProtection() {
        // '=' prefix (e.g. executable command)
        String res1 = CandidateExportService.escapeCsvField("=cmd|' /C calc'!A0");
        assertTrue(res1.startsWith("\"'="), "Formula starting with '=' must be prefixed with single quote and quoted: " + res1);

        // '+' prefix (e.g. expression)
        String res2 = CandidateExportService.escapeCsvField("+12345");
        assertTrue(res2.startsWith("\"'+"), "Formula starting with '+' must be prefixed with single quote and quoted: " + res2);

        // '@' prefix (e.g. Excel function)
        String res3 = CandidateExportService.escapeCsvField("@SUM(A1:A10)");
        assertTrue(res3.startsWith("\"'@"), "Formula starting with '@' must be prefixed with single quote and quoted: " + res3);

        // '-' prefix (e.g. negative expression / hyperlink)
        String res4 = CandidateExportService.escapeCsvField("-HYPERLINK(\"http://evil.com\")");
        assertTrue(res4.startsWith("\"'-"), "Formula starting with '-' must be prefixed with single quote and quoted: " + res4);

        // '\t' prefix
        String res5 = CandidateExportService.escapeCsvField("\tTabInjected");
        assertTrue(res5.startsWith("\"'\t"), "Formula starting with tab must be prefixed with single quote: " + res5);
    }

    @Test
    @DisplayName("Should correctly escape quotes, commas, and newlines per RFC 4180")
    void testRfc4180Escaping() {
        // Comma
        String res1 = CandidateExportService.escapeCsvField("Doe, John");
        assertEquals("\"Doe, John\"", res1);

        // Double quotes (must be doubled "")
        String res2 = CandidateExportService.escapeCsvField("Software \"Senior\" Engineer");
        assertEquals("\"Software \"\"Senior\"\" Engineer\"", res2);

        // Newline
        String res3 = CandidateExportService.escapeCsvField("Line1\nLine2");
        assertEquals("\"Line1\nLine2\"", res3);

        // Plain string (no quotes required)
        String res4 = CandidateExportService.escapeCsvField("Java Developer");
        assertEquals("Java Developer", res4);

        // Null value
        String res5 = CandidateExportService.escapeCsvField(null);
        assertEquals("\"\"", res5);
    }
}

