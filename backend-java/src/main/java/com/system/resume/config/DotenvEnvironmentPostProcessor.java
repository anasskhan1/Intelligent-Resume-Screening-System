package com.system.resume.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.Profiles;
import org.springframework.core.env.StandardEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Early-stage EnvironmentPostProcessor that handles profile-specific dotenv loading,
 * profile activation prior to ConfigData loading, credential isolation, and fail-closed
 * datasource validation.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DotenvEnvironmentPostProcessor.class);

    private static final String PROPERTY_SOURCE_NAME = "dotenvProperties";
    private static final Pattern DB_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{1,64}$");
    private static final Pattern DNS_LABEL_PATTERN = Pattern.compile("^[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$");

    private final Supplier<Map<String, String>> osEnvSupplier;

    public DotenvEnvironmentPostProcessor() {
        this(System::getenv);
    }

    DotenvEnvironmentPostProcessor(Supplier<Map<String, String>> osEnvSupplier) {
        this.osEnvSupplier = Objects.requireNonNull(osEnvSupplier, "osEnvSupplier must not be null");
    }

    Supplier<Map<String, String>> getOsEnvSupplier() {
        return this.osEnvSupplier;
    }

    @Override
    public int getOrder() {
        // Must execute BEFORE ConfigDataEnvironmentPostProcessor so profile activation
        // causes application-tidb.properties to be loaded by Spring Boot.
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean isTidbActive = isTidbProfileActive(environment);

        if (isTidbActive) {
            processTidbProfile(environment, application);
        } else {
            processLocalProfile(environment);
        }
    }

    // -------------------------------------------------------------------------
    // 1. Profile Detection & Early Activation
    // -------------------------------------------------------------------------

    public static boolean isTidbProfileActive(ConfigurableEnvironment environment) {
        if (environment != null) {
            for (String activeProfile : environment.getActiveProfiles()) {
                if ("tidb".equalsIgnoreCase(activeProfile)) return true;
            }
        }

        String[] candidateSources = {
                (environment != null) ? environment.getProperty("spring.profiles.active") : null,
                (environment != null) ? environment.getProperty("spring-boot.run.profiles") : null,
                System.getProperty("spring.profiles.active"),
                System.getProperty("spring-boot.run.profiles"),
                System.getenv("SPRING_PROFILES_ACTIVE"),
                System.getenv("SPRING_BOOT_RUN_PROFILES")
        };

        for (String candidate : candidateSources) {
            if (hasExactProfileToken(candidate, "tidb")) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasExactProfileToken(String profileList, String targetToken) {
        if (profileList == null || profileList.isBlank()) {
            return false;
        }
        return Arrays.stream(profileList.split(","))
                .map(String::trim)
                .anyMatch(targetToken::equalsIgnoreCase);
    }

    // -------------------------------------------------------------------------
    // 2. Profile Handlers & Credential Isolation
    // -------------------------------------------------------------------------

    private void processLocalProfile(ConfigurableEnvironment environment) {
        String localPathHook = (environment != null) ? environment.getProperty("DOTENV_LOCAL_PATH") : null;
        if (localPathHook == null) {
            localPathHook = System.getProperty("DOTENV_LOCAL_PATH");
        }

        if (!"none".equalsIgnoreCase(localPathHook)) {
            Path[] searchPaths;
            if (localPathHook != null && !localPathHook.isBlank()) {
                searchPaths = new Path[]{ Paths.get(localPathHook) };
            } else {
                searchPaths = new Path[]{
                        Paths.get(".env"),
                        Paths.get("..", ".env"),
                        Paths.get("backend-java", ".env")
                };
            }

            for (Path candidate : searchPaths) {
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    try {
                        Map<String, Object> envProperties = loadEnvPath(candidate);
                        if (!envProperties.isEmpty()) {
                            environment.getPropertySources().addLast(
                                    new MapPropertySource(PROPERTY_SOURCE_NAME, envProperties)
                            );
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // Preserve canonical local DB_PASSWORD validation
        String resolvedPassword = (environment != null) ? environment.getProperty("DB_PASSWORD") : null;
        if (resolvedPassword == null || resolvedPassword.isBlank() || "${DB_PASSWORD}".equals(resolvedPassword.trim())) {
            String directPassword = (environment != null) ? environment.getProperty("spring.datasource.password") : null;
            if (directPassword == null || directPassword.isBlank() || "${DB_PASSWORD}".equals(directPassword.trim())) {
                throw new IllegalStateException(
                        "Application startup failed: Required configuration placeholder 'DB_PASSWORD' is unresolved. " +
                        "No datasource password was supplied via environment variables or .env file."
                );
            }
        }
    }

    private void processTidbProfile(ConfigurableEnvironment environment, SpringApplication application) {
        // Explicitly activate the tidb profile before ConfigDataEnvironmentPostProcessor runs
        if (!environment.acceptsProfiles(Profiles.of("tidb"))) {
            environment.addActiveProfile("tidb");
        }

        // Test-isolation hook: DOTENV_TIDB_PATH="none" bypasses ambient .env.tidb file loading
        String tidbPathHook = environment.getProperty("DOTENV_TIDB_PATH");
        if (tidbPathHook == null) {
            tidbPathHook = System.getProperty("DOTENV_TIDB_PATH");
        }

        Map<String, Object> tidbProps = new HashMap<>();
        if (!"none".equalsIgnoreCase(tidbPathHook)) {
            File tidbEnvFile = (tidbPathHook != null && !tidbPathHook.isBlank())
                    ? new File(tidbPathHook)
                    : locateFile(".env.tidb");

            if (tidbEnvFile != null && tidbEnvFile.exists()) {
                tidbProps.putAll(loadDotenvFile(tidbEnvFile));
            }
        }

        // Detect conflicts across raw property sources, JVM properties, OS env, and .env.tidb
        Map<String, String> osEnv = (this.osEnvSupplier != null) ? this.osEnvSupplier.get() : null;
        CredentialConflictDetector.detectAllCredentialConflicts(environment, tidbProps, osEnv);

        resolveAndMapAlias(environment, tidbProps, "TIDB_HOST", "DB_HOST");
        resolveAndMapAlias(environment, tidbProps, "TIDB_PORT", "DB_PORT");
        resolveAndMapAlias(environment, tidbProps, "TIDB_DATABASE", "DB_NAME");
        resolveAndMapAlias(environment, tidbProps, "TIDB_USERNAME", "DB_USERNAME");
        resolveAndMapAlias(environment, tidbProps, "TIDB_PASSWORD", "DB_PASSWORD");

        // Enforce TiDB expected default port 4000, never 3306
        if (!tidbProps.containsKey("DB_PORT") || tidbProps.get("DB_PORT") == null || tidbProps.get("DB_PORT").toString().isBlank()) {
            tidbProps.put("DB_PORT", "4000");
        }

        validateTidbRequiredFields(tidbProps);
        addPropertySourceWithSafePrecedence(environment, "dotenvTidbProperties", tidbProps);

        // Load non-database keys from root .env (JWT_SECRET, admin bootstrap, etc.) while strictly stripping local DB credentials
        String localPathHook = environment.getProperty("DOTENV_LOCAL_PATH");
        if (localPathHook == null) {
            localPathHook = System.getProperty("DOTENV_LOCAL_PATH");
        }

        if (!"none".equalsIgnoreCase(localPathHook)) {
            File rootEnvFile = (localPathHook != null && !localPathHook.isBlank())
                    ? new File(localPathHook)
                    : locateFile(".env");

            if (rootEnvFile != null && rootEnvFile.exists()) {
                Map<String, Object> rootProps = loadDotenvFile(rootEnvFile);
                Map<String, Object> sanitizedNonDbProps = new HashMap<>();
                for (Map.Entry<String, Object> entry : rootProps.entrySet()) {
                    String k = entry.getKey();
                    if (!k.startsWith("DB_") && !k.startsWith("spring.datasource.") && !k.startsWith("SPRING_DATASOURCE_") && !k.startsWith("TIDB_")) {
                        sanitizedNonDbProps.put(k, entry.getValue());
                    }
                }
                if (!sanitizedNonDbProps.isEmpty()) {
                    environment.getPropertySources().addLast(new MapPropertySource("dotenvNonDbProperties", sanitizedNonDbProps));
                }
            }
        }

        // Register ApplicationPreparedEvent listener to validate effective URL after all config data is resolved
        if (application != null) {
            application.addListeners((ApplicationListener<ApplicationPreparedEvent>) event -> {
                validateEffectiveDatasourceConfiguration(event.getApplicationContext().getEnvironment());
            });
        }

        // Validate immediately if URL is already present (e.g. In direct unit tests)
        if (environment.containsProperty("spring.datasource.url") || environment.containsProperty("DB_URL")) {
            validateEffectiveDatasourceConfiguration(environment);
        }
    }

    private void addPropertySourceWithSafePrecedence(ConfigurableEnvironment env, String name, Map<String, Object> props) {
        MutablePropertySources sources = env.getPropertySources();
        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                    new MapPropertySource(name, props));
        } else if (sources.contains(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                    new MapPropertySource(name, props));
        } else {
            sources.addLast(new MapPropertySource(name, props));
        }
    }

    // -------------------------------------------------------------------------
    // 3. Effective Datasource URL Validation
    // -------------------------------------------------------------------------

    public static void validateEffectiveDatasourceConfiguration(ConfigurableEnvironment environment) {
        String dbHost = environment.getProperty("DB_HOST");
        String dbPortStr = environment.getProperty("DB_PORT");
        String dbName = environment.getProperty("DB_NAME");

        if (dbHost == null || dbHost.isBlank()) {
            throw new IllegalStateException("Security Violation: DB_HOST is required in TiDB mode.");
        }
        int dbPort = (dbPortStr == null || dbPortStr.isBlank()) ? 4000 : parseValidPort(dbPortStr, "DB_PORT");
        validateDatabaseName(dbName);
        validateHostSafety(dbHost);

        String effectiveUrl = environment.getProperty("spring.datasource.url");
        if (effectiveUrl == null || effectiveUrl.isBlank()) {
            effectiveUrl = environment.getProperty("DB_URL");
        }

        // Require effective JDBC URL to exist
        if (effectiveUrl == null || effectiveUrl.isBlank()) {
            throw new IllegalStateException("Security Violation: Effective datasource JDBC URL is required but missing in TiDB mode.");
        }

        validateJdbcUrlConsistency(effectiveUrl, dbHost, dbPort, dbName);
    }

    public static void validateDatabaseName(String dbName) {
        if (dbName == null || !DB_NAME_PATTERN.matcher(dbName).matches()) {
            throw new IllegalStateException("Security Violation: DB_NAME must be 1-64 alphanumeric/underscore characters.");
        }
    }

    public static int parseValidPort(String portStr, String fieldName) {
        if (portStr == null || portStr.isBlank()) {
            throw new IllegalStateException("Security Violation: " + fieldName + " is missing or blank.");
        }
        try {
            int port = Integer.parseInt(portStr.trim());
            if (port < 1 || port > 65535) {
                throw new IllegalStateException("Security Violation: " + fieldName + " out of valid range (1-65535): " + port);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Security Violation: " + fieldName + " must be a numeric integer.");
        }
    }

    public static void validateJdbcUrlConsistency(String jdbcUrl, String expectedHost, int expectedPort, String expectedDbName) {
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalStateException("Security Violation: JDBC URL must start with jdbc:mysql://");
        }

        if (jdbcUrl.contains("@") || jdbcUrl.contains("#")) {
            throw new IllegalStateException("Security Violation: JDBC URL contains forbidden characters (@ or #).");
        }

        // Check for insecure or relaxed SSL configuration first
        if (jdbcUrl.contains("sslMode=DISABLED") || jdbcUrl.contains("sslMode=PREFERRED") ||
            jdbcUrl.contains("sslMode=REQUIRED") || jdbcUrl.contains("sslMode=VERIFY_CA") ||
            jdbcUrl.contains("useSSL=false")) {
            throw new IllegalStateException("Security Violation: Insecure or relaxed SSL configuration detected in JDBC URL.");
        }

        // Enforce sslMode=VERIFY_IDENTITY on the effective URL
        if (!jdbcUrl.contains("sslMode=VERIFY_IDENTITY")) {
            throw new IllegalStateException("Security Violation: TiDB connection requires sslMode=VERIFY_IDENTITY in JDBC URL.");
        }

        String afterScheme = jdbcUrl.substring("jdbc:mysql://".length());
        int pathSeparatorIdx = afterScheme.indexOf('/');
        if (pathSeparatorIdx <= 0) {
            throw new IllegalStateException("Security Violation: JDBC URL missing database path delimiter.");
        }

        String authority = afterScheme.substring(0, pathSeparatorIdx);
        String afterAuthority = afterScheme.substring(pathSeparatorIdx);

        int queryIdx = afterAuthority.indexOf('?');
        String path = (queryIdx >= 0) ? afterAuthority.substring(0, queryIdx) : afterAuthority;
        String expectedPath = "/" + expectedDbName;
        if (!expectedPath.equalsIgnoreCase(path)) {
            throw new IllegalStateException("Security Violation: JDBC URL database path does not match DB_NAME.");
        }

        String extractedHost;
        int extractedPort;

        if (authority.startsWith("[")) {
            int closingBracket = authority.indexOf(']');
            if (closingBracket < 0) {
                throw new IllegalStateException("Security Violation: Malformed bracketed IPv6 in JDBC URL authority.");
            }
            extractedHost = authority.substring(1, closingBracket);
            String remainder = authority.substring(closingBracket + 1);
            if (remainder.isEmpty()) {
                extractedPort = 4000;
            } else if (remainder.startsWith(":")) {
                extractedPort = parseValidPort(remainder.substring(1), "JDBC Authority Port");
            } else {
                throw new IllegalStateException("Security Violation: Malformed characters following IPv6 closing bracket.");
            }
        } else {
            int colonIdx = authority.indexOf(':');
            if (colonIdx >= 0) {
                if (authority.indexOf(':', colonIdx + 1) >= 0) {
                    throw new IllegalStateException("Security Violation: Unbracketed IPv6 or multiple colons in authority.");
                }
                extractedHost = authority.substring(0, colonIdx);
                extractedPort = parseValidPort(authority.substring(colonIdx + 1), "JDBC Authority Port");
            } else {
                extractedHost = authority;
                extractedPort = 4000; // TiDB expected default port, never 3306
            }
        }

        if (!extractedHost.equalsIgnoreCase(expectedHost)) {
            throw new IllegalStateException("Security Violation: JDBC URL host does not match DB_HOST.");
        }
        if (extractedPort != expectedPort) {
            throw new IllegalStateException("Security Violation: JDBC URL port does not match DB_PORT.");
        }

        validateHostSafety(extractedHost);
    }

    // -------------------------------------------------------------------------
    // 4. Fail-Closed IP Literal and Hostname Safety (Zero DNS Lookups)
    // -------------------------------------------------------------------------

    public static void validateHostSafety(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("Security Violation: Host must not be blank.");
        }

        String normalizedHost = host.trim();
        if (normalizedHost.startsWith("[") && normalizedHost.endsWith("]")) {
            normalizedHost = normalizedHost.substring(1, normalizedHost.length() - 1);
        }

        if (isIpCandidate(normalizedHost)) {
            byte[] ipv4 = parseIpv4Literal(normalizedHost);
            if (ipv4 != null) {
                validateIpv4AddressRange(ipv4);
                return;
            }

            byte[] ipv6 = parseIpv6Literal(normalizedHost);
            if (ipv6 != null) {
                validateIpv6AddressRange(ipv6);
                return;
            }

            throw new IllegalStateException("Security Violation: Host appears to be an IP literal but is malformed.");
        }

        validateDnsHostname(normalizedHost);
    }

    public static boolean isIpCandidate(String host) {
        if (host.contains(":") || host.contains("[") || host.contains("]")) {
            return true;
        }
        if (host.isEmpty()) return false;
        char first = host.charAt(0);
        char last = host.charAt(host.length() - 1);
        return Character.isDigit(first) || Character.isDigit(last) || last == '.';
    }

    public static byte[] parseIpv4Literal(String ipStr) {
        String[] parts = ipStr.split("\\.", -1);
        if (parts.length != 4) return null;

        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3) return null;
            for (char c : part.toCharArray()) {
                if (c < '0' || c > '9') return null;
            }
            if (part.length() > 1 && part.startsWith("0")) return null;
            try {
                int val = Integer.parseInt(part);
                if (val < 0 || val > 255) return null;
                bytes[i] = (byte) val;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return bytes;
    }

    public static byte[] parseIpv6Literal(String ipStr) {
        if (ipStr.startsWith(":") && !ipStr.startsWith("::")) return null;
        if (ipStr.endsWith(":") && !ipStr.endsWith("::")) return null;

        String[] doubleColonParts = ipStr.split("::", -1);
        if (doubleColonParts.length > 2) return null;

        boolean isCompressed = doubleColonParts.length == 2;
        List<String> headSegments = parseSegments(doubleColonParts[0]);
        List<String> tailSegments = isCompressed ? parseSegments(doubleColonParts[1]) : Collections.emptyList();

        if (headSegments == null || tailSegments == null) return null;

        byte[] embeddedIpv4 = null;
        if (!tailSegments.isEmpty()) {
            String last = tailSegments.get(tailSegments.size() - 1);
            if (last.contains(".")) {
                embeddedIpv4 = parseIpv4Literal(last);
                if (embeddedIpv4 == null) return null;
                tailSegments.remove(tailSegments.size() - 1);
            }
        } else if (!headSegments.isEmpty() && !isCompressed) {
            String last = headSegments.get(headSegments.size() - 1);
            if (last.contains(".")) {
                embeddedIpv4 = parseIpv4Literal(last);
                if (embeddedIpv4 == null) return null;
                headSegments.remove(headSegments.size() - 1);
            }
        }

        int maxSegments = (embeddedIpv4 != null) ? 6 : 8;
        int totalSegments = headSegments.size() + tailSegments.size();

        if (isCompressed) {
            if (totalSegments >= maxSegments) return null;
        } else {
            if (totalSegments != maxSegments) return null;
        }

        byte[] bytes = new byte[16];
        int byteIdx = 0;

        for (String seg : headSegments) {
            int val = parseHexWord(seg);
            if (val < 0) return null;
            bytes[byteIdx++] = (byte) ((val >> 8) & 0xFF);
            bytes[byteIdx++] = (byte) (val & 0xFF);
        }

        if (isCompressed) {
            int omittedSegments = maxSegments - totalSegments;
            byteIdx += omittedSegments * 2;
        }

        for (String seg : tailSegments) {
            int val = parseHexWord(seg);
            if (val < 0) return null;
            bytes[byteIdx++] = (byte) ((val >> 8) & 0xFF);
            bytes[byteIdx++] = (byte) (val & 0xFF);
        }

        if (embeddedIpv4 != null) {
            System.arraycopy(embeddedIpv4, 0, bytes, 12, 4);
        }

        return bytes;
    }

    private static List<String> parseSegments(String part) {
        if (part.isEmpty()) return new ArrayList<>();
        String[] segs = part.split(":", -1);
        List<String> list = new ArrayList<>();
        for (String s : segs) {
            if (s.isEmpty()) return null;
            list.add(s);
        }
        return list;
    }

    private static int parseHexWord(String seg) {
        if (seg.isEmpty() || seg.length() > 4) return -1;
        for (char c : seg.toCharArray()) {
            if (Character.digit(c, 16) < 0) return -1;
        }
        return Integer.parseInt(seg, 16);
    }

    public static void validateIpv4AddressRange(byte[] b) {
        int o1 = b[0] & 0xFF;
        int o2 = b[1] & 0xFF;

        if (o1 == 0) throw new IllegalStateException("Security Violation: Unspecified IPv4 address.");
        if (o1 == 127) throw new IllegalStateException("Security Violation: IPv4 loopback address is forbidden.");
        if (o1 == 10) throw new IllegalStateException("Security Violation: Private RFC 1918 (10.0.0.0/8) is forbidden.");
        if (o1 == 172 && (o2 >= 16 && o2 <= 31)) throw new IllegalStateException("Security Violation: Private RFC 1918 (172.16.0.0/12) is forbidden.");
        if (o1 == 192 && o2 == 168) throw new IllegalStateException("Security Violation: Private RFC 1918 (192.168.0.0/16) is forbidden.");
        if (o1 == 169 && o2 == 254) throw new IllegalStateException("Security Violation: Link-local (169.254.0.0/16) is forbidden.");
        if (o1 == 100 && (o2 >= 64 && o2 <= 127)) throw new IllegalStateException("Security Violation: Carrier-Grade NAT (100.64.0.0/10) is forbidden.");
        if (o1 == 255 && o2 == 255 && (b[2] & 0xFF) == 255 && (b[3] & 0xFF) == 255) {
            throw new IllegalStateException("Security Violation: IPv4 broadcast address is forbidden.");
        }
    }

    public static void validateIpv6AddressRange(byte[] b) {
        boolean allZeros = true;
        for (byte x : b) {
            if (x != 0) { allZeros = false; break; }
        }
        if (allZeros) throw new IllegalStateException("Security Violation: Unspecified IPv6 address is forbidden.");

        boolean isLoopback = true;
        for (int i = 0; i < 15; i++) {
            if (b[i] != 0) { isLoopback = false; break; }
        }
        if (isLoopback && b[15] == 1) throw new IllegalStateException("Security Violation: IPv6 loopback address (::1) is forbidden.");

        if ((b[0] & 0xFF) == 0xFE && (b[1] & 0xC0) == 0x80) {
            throw new IllegalStateException("Security Violation: IPv6 link-local address (fe80::/10) is forbidden.");
        }

        if ((b[0] & 0xFE) == 0xFC) {
            throw new IllegalStateException("Security Violation: IPv6 unique-local address (fc00::/7) is forbidden.");
        }

        boolean isIpv4Mapped = true;
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) { isIpv4Mapped = false; break; }
        }
        if (isIpv4Mapped && (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF) {
            byte[] v4 = new byte[]{b[12], b[13], b[14], b[15]};
            validateIpv4AddressRange(v4);
        }
    }

    public static void validateDnsHostname(String hostname) {
        if (hostname.length() > 253) {
            throw new IllegalStateException("Security Violation: Hostname exceeds 253 characters.");
        }

        String lower = hostname.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local") || lower.equals("host.docker.internal")) {
            throw new IllegalStateException("Security Violation: Local or internal hostname is forbidden in TiDB mode.");
        }

        if (!hostname.contains(".")) {
            throw new IllegalStateException("Security Violation: Hostname must be fully qualified (must contain at least one dot).");
        }

        String[] labels = hostname.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63) {
                throw new IllegalStateException("Security Violation: Hostname label length must be between 1 and 63 characters.");
            }
            if (!DNS_LABEL_PATTERN.matcher(label).matches()) {
                throw new IllegalStateException("Security Violation: Hostname label contains invalid characters.");
            }
        }

        String tld = labels[labels.length - 1];
        if (tld.chars().allMatch(Character::isDigit)) {
            throw new IllegalStateException("Security Violation: Top-level domain must not be all-numeric.");
        }
    }

    // -------------------------------------------------------------------------
    // 5. Dotenv Helpers
    // -------------------------------------------------------------------------

    private static void resolveAndMapAlias(ConfigurableEnvironment env, Map<String, Object> props, String primaryKey, String canonicalKey) {
        String value = (env != null) ? env.getProperty(primaryKey) : null;
        if (value == null && props.containsKey(primaryKey)) {
            Object obj = props.get(primaryKey);
            value = (obj != null) ? obj.toString() : null;
        }
        if (value == null && env != null) {
            value = env.getProperty(canonicalKey);
        }
        if (value != null && !value.isBlank()) {
            props.put(canonicalKey, value);
        }
    }

    private static void validateTidbRequiredFields(Map<String, Object> props) {
        String[] required = {"DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD"};
        for (String key : required) {
            Object val = props.get(key);
            if (val == null || val.toString().isBlank()) {
                throw new IllegalStateException("Security Violation: Missing required TiDB configuration property: " + key);
            }
        }
    }

    private static Map<String, Object> loadDotenvFile(File file) {
        try {
            return loadEnvPath(file.toPath());
        } catch (IOException e) {
            log.warn("Could not read dotenv file: {}", file.getAbsolutePath());
            return new HashMap<>();
        }
    }

    private static Map<String, Object> loadEnvPath(Path path) throws IOException {
        Map<String, Object> props = new HashMap<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                if (line.startsWith("export ") && line.length() > 7) {
                    line = line.substring(7).trim();
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    if ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'"))) {
                        if (val.length() >= 2) {
                            val = val.substring(1, val.length() - 1);
                        }
                    }
                    if (!key.isEmpty()) {
                        props.put(key, val);
                    }
                }
            }
        }
        return props;
    }

    private static File locateFile(String filename) {
        File f = new File(filename);
        if (f.exists()) return f;
        f = new File("..", filename);
        if (f.exists()) return f;
        f = new File("backend-java", filename);
        if (f.exists()) return f;
        return null;
    }
}
