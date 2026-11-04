package com.system.resume.config;

import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.util.*;

/**
 * Inspects every individual PropertySource registered in the Spring Environment,
 * the raw OS environment, the raw JVM system properties, and the parsed .env.tidb map.
 *
 * Detects conflicts:
 * 1. When the SAME key has conflicting values across different property sources.
 * 2. When DIFFERENT alias keys have conflicting values across any sources.
 *
 * Distinguishes placeholder references (e.g., ${DB_USERNAME}) from literal credentials.
 * NEVER logs or prints actual credential values.
 */
public class CredentialConflictDetector {

    /**
     * Production entry point: inspects OS environment using System.getenv().
     */
    public static void detectAllCredentialConflicts(
            ConfigurableEnvironment environment,
            Map<String, Object> tidbFileProps) {
        detectAllCredentialConflicts(environment, tidbFileProps, System.getenv());
    }

    /**
     * Injectable entry point: allows explicit OS environment map injection for testing.
     */
    public static void detectAllCredentialConflicts(
            ConfigurableEnvironment environment,
            Map<String, Object> tidbFileProps,
            Map<String, String> osEnv) {

        String[] usernameAliases = {
                "TIDB_USERNAME",
                "DB_USERNAME",
                "spring.datasource.username",
                "SPRING_DATASOURCE_USERNAME"
        };

        String[] passwordAliases = {
                "TIDB_PASSWORD",
                "DB_PASSWORD",
                "spring.datasource.password",
                "SPRING_DATASOURCE_PASSWORD"
        };

        inspectAliasGroup(environment, tidbFileProps, osEnv, usernameAliases, "Username");
        inspectAliasGroup(environment, tidbFileProps, osEnv, passwordAliases, "Password");
    }

    private static void inspectAliasGroup(
            ConfigurableEnvironment environment,
            Map<String, Object> tidbFileProps,
            Map<String, String> osEnv,
            String[] aliasKeys,
            String fieldName) {

        Set<String> aliasKeySet = new HashSet<>(Arrays.asList(aliasKeys));

        String baselineValue = null;
        String baselineSource = null;

        // 1. Inspect raw JVM System Properties
        Properties sysProps = System.getProperties();
        for (String key : aliasKeys) {
            String raw = sysProps.getProperty(key);
            String val = resolveCredentialValue(raw, environment, tidbFileProps, aliasKeySet);
            if (val != null) {
                if (baselineValue == null) {
                    baselineValue = val;
                    baselineSource = "JVM system property '" + key + "'";
                } else if (!baselineValue.equals(val)) {
                    throwConflictException(fieldName, baselineSource, "JVM system property '" + key + "'");
                }
            }
        }

        // 2. Inspect supplied OS Environment Variables
        if (osEnv != null) {
            for (String key : aliasKeys) {
                String raw = osEnv.get(key);
                String val = resolveCredentialValue(raw, environment, tidbFileProps, aliasKeySet);
                if (val != null) {
                    if (baselineValue == null) {
                        baselineValue = val;
                        baselineSource = "OS environment variable '" + key + "'";
                    } else if (!baselineValue.equals(val)) {
                        throwConflictException(fieldName, baselineSource, "OS environment variable '" + key + "'");
                    }
                }
            }
        }

        // 3. Inspect parsed .env.tidb map
        if (tidbFileProps != null) {
            for (String key : aliasKeys) {
                Object rawObj = tidbFileProps.get(key);
                if (rawObj != null) {
                    String val = resolveCredentialValue(rawObj.toString(), environment, tidbFileProps, aliasKeySet);
                    if (val != null) {
                        if (baselineValue == null) {
                            baselineValue = val;
                            baselineSource = ".env.tidb key '" + key + "'";
                        } else if (!baselineValue.equals(val)) {
                            throwConflictException(fieldName, baselineSource, ".env.tidb key '" + key + "'");
                        }
                    }
                }
            }
        }

        // 4. Inspect each registered Spring PropertySource
        if (environment != null && environment.getPropertySources() != null) {
            for (PropertySource<?> ps : environment.getPropertySources()) {
                String psName = ps.getName();
                if (psName.equals("systemProperties") || psName.equals("systemEnvironment")) {
                    continue;
                }

                for (String key : aliasKeys) {
                    if (ps.containsProperty(key)) {
                        Object rawObj = ps.getProperty(key);
                        if (rawObj != null) {
                            String val = resolveCredentialValue(rawObj.toString(), environment, tidbFileProps, aliasKeySet);
                            if (val != null) {
                                if (baselineValue == null) {
                                    baselineValue = val;
                                    baselineSource = "PropertySource [" + psName + "] key '" + key + "'";
                                } else if (!baselineValue.equals(val)) {
                                    throwConflictException(fieldName, baselineSource, "PropertySource [" + psName + "] key '" + key + "'");
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public static String resolveCredentialValue(
            String rawVal,
            ConfigurableEnvironment env,
            Map<String, Object> tidbFileProps,
            Set<String> aliasKeys) {

        if (rawVal == null || rawVal.isBlank()) {
            return null;
        }
        String trimmed = rawVal.trim();
        if (!trimmed.contains("${")) {
            return trimmed; // Explicit literal credential
        }

        // Attempt placeholder resolution via Spring Environment if env is provided
        String resolved = null;
        if (env != null) {
            try {
                resolved = env.resolvePlaceholders(trimmed);
            } catch (Exception ignored) {
            }
        }

        // Attempt substitution from tidbFileProps if still contains placeholders
        if (resolved == null || resolved.contains("${")) {
            if (tidbFileProps != null) {
                for (Map.Entry<String, Object> entry : tidbFileProps.entrySet()) {
                    String placeholder = "${" + entry.getKey() + "}";
                    if (trimmed.contains(placeholder) && entry.getValue() != null) {
                        trimmed = trimmed.replace(placeholder, entry.getValue().toString().trim());
                    }
                }
                resolved = trimmed;
            }
        }

        // If after resolution it still contains unresolved placeholder syntax:
        if (resolved != null && resolved.contains("${")) {
            // Check if it is delegating to an alias within the same group (e.g. ${DB_USERNAME})
            for (String alias : aliasKeys) {
                if (resolved.contains("${" + alias + "}") || resolved.contains("${" + alias + ":")) {
                    return null; // Benign unresolved alias indirection; ignore
                }
            }
            return null;
        }

        return (resolved != null && !resolved.isBlank()) ? resolved : null;
    }

    private static void throwConflictException(String fieldName, String source1, String source2) {
        throw new IllegalStateException(
                "Security Violation: Conflicting " + fieldName + " configuration detected between " +
                source1 + " and " + source2 + ". All " + fieldName + " aliases and definitions must match across all sources."
        );
    }
}
