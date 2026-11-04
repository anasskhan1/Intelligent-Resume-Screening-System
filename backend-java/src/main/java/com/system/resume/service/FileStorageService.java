package com.system.resume.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Service managing physical filesystem operations for uploaded resume files.
 * Handles storage directory initialization, unique path generation, file validation,
 * and immediate disk cleanup upon failure.
 */
@Service
public class FileStorageService {

    private static final Logger logger = LoggerFactory.getLogger(FileStorageService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt");

    private final Path uploadLocation;

    public FileStorageService(@Value("${file.upload-dir:uploads/resumes}") String uploadDir) {
        this.uploadLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.uploadLocation);
            logger.info("Resume file storage directory initialized at: {}", this.uploadLocation);
        } catch (IOException e) {
            logger.error("Could not create resume upload directory at: {}", this.uploadLocation, e);
            throw new RuntimeException("Could not initialize storage directory: " + this.uploadLocation, e);
        }
    }

    /**
     * Validates and saves an uploaded resume file to disk with a collision-resistant unique name.
     * Enforces file extension and binary magic-byte signature validation before writing to disk.
     *
     * @param file the multipart file to store
     * @return the normalized absolute file path string
     */
    public String storeFile(MultipartFile file) {
        validateFileContent(file);

        String originalFilename = StringUtils.cleanPath(Objects.requireNonNullElse(file.getOriginalFilename(), "resume"));
        String uniqueFilename = UUID.randomUUID() + "_" + originalFilename;
        Path targetPath = this.uploadLocation.resolve(uniqueFilename).normalize();

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            logger.info("Stored uploaded resume file at: {}", targetPath);
            return targetPath.toString();
        } catch (IOException e) {
            logger.error("Failed to store file '{}' on disk: {}", originalFilename, e.getMessage(), e);
            throw new RuntimeException("Failed to store file on disk: " + originalFilename, e);
        }
    }

    /**
     * Inspects and validates file extension and underlying content signature (magic bytes).
     * Rejects empty files, path traversal attempts, unsupported extensions, and files whose
     * binary signatures do not match their claimed file extension.
     *
     * @param file the multipart file to validate
     * @throws IllegalArgumentException on any validation failure
     */
    public void validateFileContent(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Cannot store an empty or null file.");
        }

        String originalFilename = StringUtils.cleanPath(Objects.requireNonNullElse(file.getOriginalFilename(), "resume"));

        // Security check for relative path traversal
        if (originalFilename.contains("..")) {
            throw new IllegalArgumentException("Filename contains invalid path sequence: " + originalFilename);
        }

        // Validate allowed file extensions
        String extension = getFileExtension(originalFilename).toLowerCase();
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported file type '." + extension + "'. Allowed types: " + ALLOWED_EXTENSIONS);
        }

        // Read sample bytes from start of file for magic-byte content inspection
        byte[] sampleBytes;
        try (InputStream is = file.getInputStream()) {
            sampleBytes = is.readNBytes(512);
        } catch (IOException e) {
            logger.error("Failed to read header bytes from uploaded file '{}': {}", originalFilename, e.getMessage(), e);
            throw new IllegalArgumentException("Unable to read file content for validation: " + originalFilename);
        }

        if (sampleBytes == null || sampleBytes.length == 0) {
            throw new IllegalArgumentException("Uploaded file is empty (0 bytes): " + originalFilename);
        }

        validateSignatureForExtension(extension, sampleBytes, originalFilename);

        // For DOCX files, safely inspect the ZIP directory structure to ensure genuine OOXML Word package format
        if ("docx".equals(extension)) {
            validateDocxPackage(file, originalFilename);
        }
    }

    private void validateDocxPackage(MultipartFile file, String filename) {
        try (InputStream is = file.getInputStream();
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            boolean hasWordDocument = false;
            int entryCount = 0;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > 1000) {
                    throw new IllegalArgumentException("DOCX package exceeds safe entry threshold: " + filename);
                }
                String name = entry.getName();
                if ("word/document.xml".equalsIgnoreCase(name) || name.toLowerCase().startsWith("word/")) {
                    hasWordDocument = true;
                    break;
                }
            }
            if (!hasWordDocument) {
                throw new IllegalArgumentException("File content does not match extension '.docx'. ZIP archive does not contain an OOXML Word document (missing 'word/document.xml'): " + filename);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to read DOCX package structure: " + filename, e);
        }
    }

    private void validateSignatureForExtension(String extension, byte[] sample, String filename) {
        switch (extension) {
            case "pdf" -> {
                // PDF magic bytes: '%PDF' (0x25, 0x50, 0x44, 0x46)
                if (sample.length < 4 || sample[0] != 0x25 || sample[1] != 0x50 || sample[2] != 0x44 || sample[3] != 0x46) {
                    throw new IllegalArgumentException("File content does not match extension '.pdf'. Missing valid PDF signature (%PDF): " + filename);
                }
            }
            case "docx" -> {
                // DOCX magic bytes: Microsoft Office OpenXML ZIP archive 'PK\x03\x04' (0x50, 0x4B, 0x03, 0x04)
                if (sample.length < 4 || sample[0] != 0x50 || sample[1] != 0x4B || sample[2] != 0x03 || sample[3] != 0x04) {
                    throw new IllegalArgumentException("File content does not match extension '.docx'. Missing valid OpenXML/ZIP archive signature (PK..): " + filename);
                }
            }
            case "txt" -> {
                // Reject if disguised PDF or ZIP/DOCX
                if (sample.length >= 4 && sample[0] == 0x25 && sample[1] == 0x50 && sample[2] == 0x44 && sample[3] == 0x46) {
                    throw new IllegalArgumentException("File content does not match extension '.txt'. PDF binary signature detected: " + filename);
                }
                if (sample.length >= 4 && sample[0] == 0x50 && sample[1] == 0x4B && sample[2] == 0x03 && sample[3] == 0x04) {
                    throw new IllegalArgumentException("File content does not match extension '.txt'. ZIP/DOCX binary signature detected: " + filename);
                }
                // Reject Windows executable 'MZ' (0x4D, 0x5A)
                if (sample.length >= 2 && sample[0] == 0x4D && sample[1] == 0x5A) {
                    throw new IllegalArgumentException("File content does not match extension '.txt'. Executable binary signature detected: " + filename);
                }
                // Reject Linux/UNIX executable '\x7fELF' (0x7F, 0x45, 0x4C, 0x46)
                if (sample.length >= 4 && sample[0] == 0x7F && sample[1] == 0x45 && sample[2] == 0x4C && sample[3] == 0x46) {
                    throw new IllegalArgumentException("File content does not match extension '.txt'. Executable binary signature detected: " + filename);
                }
                // Reject binary content: null bytes and non-printable control characters
                for (byte b : sample) {
                    int unsignedByte = b & 0xFF;
                    if (unsignedByte == 0) {
                        throw new IllegalArgumentException("File content does not match extension '.txt'. Binary null bytes detected: " + filename);
                    }
                    if ((unsignedByte < 0x20 && unsignedByte != 0x09 && unsignedByte != 0x0A && unsignedByte != 0x0D) || unsignedByte == 0x7F) {
                        throw new IllegalArgumentException("File content does not match extension '.txt'. Binary control characters detected: " + filename);
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unsupported file type '." + extension + "'. Allowed types: " + ALLOWED_EXTENSIONS);
        }
    }

    /**
     * Reads the byte array content of a stored file.
     *
     * @param filePath the file path to read
     * @return the byte array of the file
     */
    public byte[] loadFileAsBytes(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("File path cannot be null or empty.");
        }

        Path path = Paths.get(filePath).normalize();
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            logger.error("Failed to read file bytes from '{}': {}", filePath, e.getMessage(), e);
            throw new RuntimeException("Could not read file at: " + filePath, e);
        }
    }

    /**
     * Computes the SHA-256 checksum for the provided byte array.
     *
     * @param data the byte array to hash
     * @return a lowercase 64-character hexadecimal checksum string
     * @throws IllegalArgumentException if data is null
     * @throws IllegalStateException if the SHA-256 digest algorithm is unavailable
     */
    public String computeSha256(byte[] data) {
        if (data == null) {
            throw new IllegalArgumentException("Byte array data cannot be null for SHA-256 computation.");
        }

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(data);
            StringBuilder hexString = new StringBuilder(64);
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 algorithm not available in the current runtime environment", e);
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Safely deletes a file from disk if it exists.
     * Used for cleanup when database registration or validation fails.
     *
     * @param filePath the file path to delete
     */
    public void deleteFile(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return;
        }

        try {
            Path path = Paths.get(filePath).normalize();
            boolean deleted = Files.deleteIfExists(path);
            if (deleted) {
                logger.info("Successfully deleted file: {}", filePath);
            }
        } catch (IOException e) {
            logger.warn("Failed to delete file '{}': {}", filePath, e.getMessage());
        }
    }

    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < filename.length() - 1) {
            return filename.substring(lastDotIndex + 1);
        }
        return "";
    }
}
