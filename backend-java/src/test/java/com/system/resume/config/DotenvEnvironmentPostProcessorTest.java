package com.system.resume.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class DotenvEnvironmentPostProcessorTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class
    })
    static class TestApplication {
    }

    private static final String[] TRACKED_SYSTEM_PROPERTIES = {
            "spring.profiles.active",
            "spring-boot.run.profiles",
            "DB_HOST",
            "DB_PORT",
            "DB_NAME",
            "DB_USERNAME",
            "DB_PASSWORD",
            "TIDB_HOST",
            "TIDB_PORT",
            "TIDB_DATABASE",
            "TIDB_USERNAME",
            "TIDB_PASSWORD",
            "DOTENV_TIDB_PATH",
            "DOTENV_LOCAL_PATH"
    };

    private final Map<String, String> originalSystemProperties = new HashMap<>();

    @BeforeEach
    void setUpSystemPropertiesSnapshot() {
        originalSystemProperties.clear();
        for (String prop : TRACKED_SYSTEM_PROPERTIES) {
            originalSystemProperties.put(prop, System.getProperty(prop));
            System.clearProperty(prop);
        }
        // Isolate tests from ambient machine .env files
        System.setProperty("DOTENV_TIDB_PATH", "none");
        System.setProperty("DOTENV_LOCAL_PATH", "none");
    }

    @AfterEach
    void tearDownRestoreSystemProperties() {
        for (Map.Entry<String, String> entry : originalSystemProperties.entrySet()) {
            if (entry.getValue() != null) {
                System.setProperty(entry.getKey(), entry.getValue());
            } else {
                System.clearProperty(entry.getKey());
            }
        }
    }

    // -------------------------------------------------------------------------
    // 1. Spring Boot Lifecycle & ConfigData Loading Verification
    // -------------------------------------------------------------------------

    @Test
    void testMavenLaunch_ActivatesTidbProfile_AndLoadsApplicationTidbProperties() {
        // Simulates: mvn spring-boot:run -Dspring-boot.run.profiles=tidb
        System.setProperty("spring-boot.run.profiles", "tidb");

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "DB_HOST=gateway01.ap-southeast-1.prod.aws.tidbcloud.com",
                        "DB_NAME=resume_screening_db",
                        "DB_USERNAME=test_cloud_user.root",
                        "DB_PASSWORD=test_cloud_password",
                        "spring.autoconfigure.exclude=" +
                                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                                "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
                )
                .run()) {

            ConfigurableEnvironment env = context.getEnvironment();

            // 1. Verify profile was activated before ConfigData processed
            assertThat(env.getActiveProfiles()).contains("tidb");

            // 2. Verify application-tidb.properties was actually loaded by ConfigData
            assertThat(env.getProperty("spring.datasource.hikari.pool-name")).isEqualTo("HikariPool-TiDB");
            assertThat(env.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("3");
            assertThat(env.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("0");

            // 3. Verify effective URL was composed with sslMode=VERIFY_IDENTITY and port 4000
            String effectiveUrl = env.getProperty("spring.datasource.url");
            assertThat(effectiveUrl).isNotNull();
            assertThat(effectiveUrl).startsWith("jdbc:mysql://gateway01.ap-southeast-1.prod.aws.tidbcloud.com:4000/resume_screening_db");
            assertThat(effectiveUrl).contains("sslMode=VERIFY_IDENTITY");
        }
    }

    @Test
    void testApplicationPreparedEvent_InvalidEndpoint_AbortsBeforeHikariInitializes() {
        System.setProperty("spring-boot.run.profiles", "tidb");

        assertThatThrownBy(() -> {
            new SpringApplicationBuilder(TestApplication.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "DB_HOST=127.0.0.1", // Prohibited loopback in TiDB mode
                            "DB_PORT=4000",
                            "DB_NAME=resume_screening_db",
                            "DB_USERNAME=test_user",
                            "DB_PASSWORD=test_password",
                            "spring.autoconfigure.exclude=" +
                                    "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                                    "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration," +
                                    "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration"
                    )
                    .run();
        })
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("IPv4 loopback address is forbidden");
    }

    @Test
    void testApplicationPreparedEvent_MissingJdbcUrl_FailsFast() {
        ConfigurableEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new HashMap<>();
        props.put("DB_HOST", "gateway01.ap-southeast-1.prod.aws.tidbcloud.com");
        props.put("DB_PORT", "4000");
        props.put("DB_NAME", "resume_screening_db");
        props.put("DB_USERNAME", "test_user");
        props.put("DB_PASSWORD", "test_password");
        // spring.datasource.url and DB_URL are deliberately omitted
        env.getPropertySources().addFirst(new MapPropertySource("testProps", props));

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateEffectiveDatasourceConfiguration(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Effective datasource JDBC URL is required but missing in TiDB mode");
    }

    @Test
    void testApplicationPreparedEvent_RelaxedSslMode_FailsFast() {
        ConfigurableEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new HashMap<>();
        props.put("DB_HOST", "gateway01.ap-southeast-1.prod.aws.tidbcloud.com");
        props.put("DB_PORT", "4000");
        props.put("DB_NAME", "resume_screening_db");
        props.put("spring.datasource.url", "jdbc:mysql://gateway01.ap-southeast-1.prod.aws.tidbcloud.com:4000/resume_screening_db?sslMode=PREFERRED");
        env.getPropertySources().addFirst(new MapPropertySource("testProps", props));

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateEffectiveDatasourceConfiguration(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Insecure or relaxed SSL configuration detected in JDBC URL");
    }

    // -------------------------------------------------------------------------
    // 2. Credential Isolation & Conflict Detection Verification
    // -------------------------------------------------------------------------

    @Test
    void testCredentialConflict_MatchingActualCredentialsWithPlaceholderProperties_Succeeds() {
        ConfigurableEnvironment env = new StandardEnvironment();

        Map<String, Object> springProps = new HashMap<>();
        springProps.put("spring.datasource.username", "${DB_USERNAME}");
        springProps.put("spring.datasource.password", "${DB_PASSWORD}");
        env.getPropertySources().addFirst(new MapPropertySource("applicationProperties", springProps));

        Map<String, Object> tidbFileProps = new HashMap<>();
        tidbFileProps.put("DB_USERNAME", "tidb_valid_user.root");
        tidbFileProps.put("DB_PASSWORD", "tidb_valid_pass_123");

        CredentialConflictDetector.detectAllCredentialConflicts(env, tidbFileProps);
    }

    @Test
    void testCredentialConflict_ConflictingActualCredentialsAcrossSources_FailsFast() {
        ConfigurableEnvironment env = new StandardEnvironment();
        System.setProperty("DB_USERNAME", "admin_user_A");

        Map<String, Object> tidbFileProps = new HashMap<>();
        tidbFileProps.put("TIDB_USERNAME", "admin_user_B");

        assertThatThrownBy(() -> CredentialConflictDetector.detectAllCredentialConflicts(env, tidbFileProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting Username configuration detected")
                .hasMessageNotContaining("admin_user_A")
                .hasMessageNotContaining("admin_user_B");
    }

    @Test
    void testCredentialConflict_LiteralSpringDatasourceCredentials_ConflictingWithDbUsername_FailsFast() {
        ConfigurableEnvironment env = new StandardEnvironment();

        Map<String, Object> directProps = new HashMap<>();
        directProps.put("spring.datasource.username", "hardcoded_direct_user");
        env.getPropertySources().addFirst(new MapPropertySource("customDatasourceProps", directProps));

        Map<String, Object> tidbFileProps = new HashMap<>();
        tidbFileProps.put("DB_USERNAME", "cloud_tidb_user.root");

        assertThatThrownBy(() -> CredentialConflictDetector.detectAllCredentialConflicts(env, tidbFileProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting Username configuration detected")
                .hasMessageNotContaining("hardcoded_direct_user")
                .hasMessageNotContaining("cloud_tidb_user.root");
    }

    @Test
    void testCredentialConflict_DifferentAliasesDiffering_FailsFastWithoutLeaking() {
        ConfigurableEnvironment env = new StandardEnvironment();
        System.setProperty("DB_PASSWORD", "secret_pass_A");

        Map<String, Object> tidbFileProps = new HashMap<>();
        tidbFileProps.put("TIDB_PASSWORD", "secret_pass_B");

        assertThatThrownBy(() -> CredentialConflictDetector.detectAllCredentialConflicts(env, tidbFileProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting Password configuration detected")
                .hasMessageNotContaining("secret_pass_A")
                .hasMessageNotContaining("secret_pass_B");
    }

    @Test
    void testCredentialConflict_EmptyInjectedOsEnv_Succeeds() {
        ConfigurableEnvironment env = new StandardEnvironment();
        Map<String, Object> tidbFileProps = new HashMap<>();
        tidbFileProps.put("DB_USERNAME", "cloud_tidb_user.root");
        tidbFileProps.put("DB_PASSWORD", "cloud_tidb_password");

        // 1. Direct detector call with empty injected OS environment
        CredentialConflictDetector.detectAllCredentialConflicts(env, tidbFileProps, Collections.emptyMap());

        // 2. Full DotenvEnvironmentPostProcessor execution with empty injected OS environment supplier
        DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor(Collections::emptyMap);
        Map<String, Object> props = new HashMap<>();
        props.put("DB_HOST", "gateway01.ap-southeast-1.prod.aws.tidbcloud.com");
        props.put("DB_PORT", "4000");
        props.put("DB_NAME", "resume_screening_db");
        props.put("DB_USERNAME", "cloud_tidb_user.root");
        props.put("DB_PASSWORD", "cloud_tidb_password");
        props.put("spring.profiles.active", "tidb");
        props.put("DOTENV_TIDB_PATH", "none");
        props.put("DOTENV_LOCAL_PATH", "none");
        env.getPropertySources().addFirst(new MapPropertySource("testTiDBProps", props));

        processor.postProcessEnvironment(env, null);
    }

    @Test
    void testCredentialConflict_MatchingInjectedOsEnv_Succeeds() {
        ConfigurableEnvironment env = new StandardEnvironment();
        Map<String, Object> tidbFileProps = new HashMap<>();
        tidbFileProps.put("DB_USERNAME", "matching_cloud_user.root");
        tidbFileProps.put("DB_PASSWORD", "matching_cloud_password");

        Map<String, String> injectedOsEnv = new HashMap<>();
        injectedOsEnv.put("TIDB_USERNAME", "matching_cloud_user.root");
        injectedOsEnv.put("TIDB_PASSWORD", "matching_cloud_password");

        // Same credentials in injected OS environment and .env.tidb must succeed
        CredentialConflictDetector.detectAllCredentialConflicts(env, tidbFileProps, injectedOsEnv);

        // Also test through DotenvEnvironmentPostProcessor with matching supplier
        DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor(() -> injectedOsEnv);
        Map<String, Object> props = new HashMap<>();
        props.put("DB_HOST", "gateway01.ap-southeast-1.prod.aws.tidbcloud.com");
        props.put("DB_PORT", "4000");
        props.put("DB_NAME", "resume_screening_db");
        props.put("DB_USERNAME", "matching_cloud_user.root");
        props.put("DB_PASSWORD", "matching_cloud_password");
        props.put("spring.profiles.active", "tidb");
        props.put("DOTENV_TIDB_PATH", "none");
        props.put("DOTENV_LOCAL_PATH", "none");
        env.getPropertySources().addFirst(new MapPropertySource("testTiDBProps", props));

        processor.postProcessEnvironment(env, null);
    }

    @Test
    void testCredentialConflict_ConflictingInjectedOsEnv_FailsFastWithoutLeaking() {
        ConfigurableEnvironment env = new StandardEnvironment();
        Map<String, Object> tidbFileProps = new HashMap<>();
        tidbFileProps.put("DB_USERNAME", "super_secret_user_A");
        tidbFileProps.put("DB_PASSWORD", "super_secret_pass_A");

        Map<String, String> injectedOsEnv = new HashMap<>();
        injectedOsEnv.put("TIDB_USERNAME", "super_secret_user_B");
        injectedOsEnv.put("TIDB_PASSWORD", "super_secret_pass_B");

        // 1. Direct detector test with injected OS environment fails fast and does not leak secrets
        assertThatThrownBy(() -> CredentialConflictDetector.detectAllCredentialConflicts(env, tidbFileProps, injectedOsEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting Username configuration detected")
                .hasMessageNotContaining("super_secret_user_A")
                .hasMessageNotContaining("super_secret_user_B")
                .hasMessageNotContaining("super_secret_pass_A")
                .hasMessageNotContaining("super_secret_pass_B");

        // 2. End-to-end DotenvEnvironmentPostProcessor with injected supplier fails fast without leaking
        DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor(() -> injectedOsEnv);
        Map<String, Object> props = new HashMap<>();
        props.put("DB_HOST", "gateway01.ap-southeast-1.prod.aws.tidbcloud.com");
        props.put("DB_NAME", "resume_screening_db");
        props.put("DB_USERNAME", "super_secret_user_A");
        props.put("DB_PASSWORD", "super_secret_pass_A");
        props.put("spring.profiles.active", "tidb");
        props.put("DOTENV_TIDB_PATH", "none");
        props.put("DOTENV_LOCAL_PATH", "none");
        env.getPropertySources().addFirst(new MapPropertySource("testTiDBProps", props));

        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting Username configuration detected")
                .hasMessageNotContaining("super_secret_user_A")
                .hasMessageNotContaining("super_secret_user_B");
    }

    @Test
    void testCredentialConflict_ProductionDefaultProvider_GenuinelyReadsSystemGetenv() {
        DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor();
        Supplier<Map<String, String>> supplier = processor.getOsEnvSupplier();
        assertThat(supplier).isNotNull();

        Map<String, String> providedEnv = supplier.get();
        assertThat(providedEnv).isNotNull();

        // Genuinely proves that the supplier's .get() returns the actual OS environment
        Map<String, String> actualSystemEnv = System.getenv();
        assertThat(providedEnv).isEqualTo(actualSystemEnv);

        boolean hasCommonEnvVar = providedEnv.containsKey("PATH") || providedEnv.containsKey("Path") || providedEnv.containsKey("TEMP");
        assertThat(hasCommonEnvVar).isTrue();
    }

    @Test
    void testDeterministicUnderAmbientOsCredentials_SimulatedPollution() {
        // Simulate ambient OS environment containing conflicting host credentials
        Map<String, String> pollutedHostOsEnv = new HashMap<>();
        pollutedHostOsEnv.put("DB_USERNAME", "ambient_workstation_user");
        pollutedHostOsEnv.put("DB_PASSWORD", "ambient_workstation_secret");
        pollutedHostOsEnv.put("TIDB_USERNAME", "ambient_workstation_tidb_user");

        // 1. With production detector evaluating pollutedHostOsEnv, it fails fast
        ConfigurableEnvironment env = new StandardEnvironment();
        Map<String, Object> tidbProps = new HashMap<>();
        tidbProps.put("DB_USERNAME", "project_tidb_user.root");
        tidbProps.put("DB_PASSWORD", "project_tidb_password");

        assertThatThrownBy(() -> CredentialConflictDetector.detectAllCredentialConflicts(env, tidbProps, pollutedHostOsEnv))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Conflicting Username configuration detected");

        // 2. With injected isolated OS environment (emptyMap), the test runs 100% deterministically
        DotenvEnvironmentPostProcessor isolatedProcessor = new DotenvEnvironmentPostProcessor(Collections::emptyMap);
        assertThat(isolatedProcessor.getOsEnvSupplier().get()).isEmpty();

        CredentialConflictDetector.detectAllCredentialConflicts(env, tidbProps, isolatedProcessor.getOsEnvSupplier().get());

        // Processor postProcessEnvironment also runs deterministically without conflict
        Map<String, Object> props = new HashMap<>();
        props.put("DB_HOST", "gateway01.ap-southeast-1.prod.aws.tidbcloud.com");
        props.put("DB_PORT", "4000");
        props.put("DB_NAME", "resume_screening_db");
        props.put("DB_USERNAME", "project_tidb_user.root");
        props.put("DB_PASSWORD", "project_tidb_password");
        props.put("spring.profiles.active", "tidb");
        props.put("DOTENV_TIDB_PATH", "none");
        props.put("DOTENV_LOCAL_PATH", "none");
        env.getPropertySources().addFirst(new MapPropertySource("testTiDBProps", props));

        isolatedProcessor.postProcessEnvironment(env, null);
    }

    // -------------------------------------------------------------------------
    // 3. Strict Port Default & Authority Parsing
    // -------------------------------------------------------------------------

    @Test
    void testPortDefaulting_TiDBDefaultsTo4000_Never3306() {
        ConfigurableEnvironment env = new StandardEnvironment();
        Map<String, Object> props = new HashMap<>();
        props.put("DB_HOST", "gateway01.ap-southeast-1.prod.aws.tidbcloud.com");
        // DB_PORT omitted
        props.put("DB_NAME", "resume_screening_db");
        props.put("spring.datasource.url", "jdbc:mysql://gateway01.ap-southeast-1.prod.aws.tidbcloud.com:4000/resume_screening_db?sslMode=VERIFY_IDENTITY");
        env.getPropertySources().addFirst(new MapPropertySource("testProps", props));

        // When DB_PORT is omitted in TiDB mode, it must accept 4000 as the valid default
        DotenvEnvironmentPostProcessor.validateEffectiveDatasourceConfiguration(env);

        // If URL defaulted to 3306 instead of 4000, it must fail
        props.put("spring.datasource.url", "jdbc:mysql://gateway01.ap-southeast-1.prod.aws.tidbcloud.com:3306/resume_screening_db?sslMode=VERIFY_IDENTITY");
        env.getPropertySources().addFirst(new MapPropertySource("testProps", props));

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateEffectiveDatasourceConfiguration(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC URL port does not match DB_PORT");
    }

    @Test
    void testPortValidation_RangeAndNumeric() {
        assertThat(DotenvEnvironmentPostProcessor.parseValidPort("4000", "TEST_PORT")).isEqualTo(4000);
        assertThat(DotenvEnvironmentPostProcessor.parseValidPort("1", "TEST_PORT")).isEqualTo(1);
        assertThat(DotenvEnvironmentPostProcessor.parseValidPort("65535", "TEST_PORT")).isEqualTo(65535);

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.parseValidPort("0", "TEST_PORT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of valid range");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.parseValidPort("65536", "TEST_PORT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("out of valid range");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.parseValidPort("invalid", "TEST_PORT"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be a numeric integer");
    }

    // -------------------------------------------------------------------------
    // 4. IP-Literal & Hostname Safety (Zero DNS Lookups)
    // -------------------------------------------------------------------------

    @Test
    void testIpv4Validation_RejectPrivateAndLocal() {
        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback address is forbidden");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("10.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Private RFC 1918 (10.0.0.0/8) is forbidden");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("192.168.1.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Private RFC 1918 (192.168.0.0/16) is forbidden");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("169.254.1.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Link-local (169.254.0.0/16) is forbidden");
    }

    @Test
    void testIpv4Validation_FailClosed_MalformedLiterals() {
        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("010.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appears to be an IP literal but is malformed");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("999.999.999.999"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appears to be an IP literal but is malformed");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("1.2.3"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appears to be an IP literal but is malformed");
    }

    @Test
    void testIpv6Validation_RejectLoopbackAndLocal() {
        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("::1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("loopback address (::1) is forbidden");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("fe80::1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("link-local address (fe80::/10) is forbidden");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("fc00::1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unique-local address (fc00::/7) is forbidden");
    }

    @Test
    void testIpv6Validation_FailClosed_MalformedLiterals() {
        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("2001::1::2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appears to be an IP literal but is malformed");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("2001:xyz::1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appears to be an IP literal but is malformed");
    }

    @Test
    void testDnsHostname_AcceptValidFQDN() {
        // Valid public FQDN must pass
        DotenvEnvironmentPostProcessor.validateHostSafety("gateway01.ap-southeast-1.prod.aws.tidbcloud.com");
    }

    @Test
    void testDnsHostname_RejectUnqualifiedAndLocal() {
        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("localhost"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local or internal hostname is forbidden in TiDB mode");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("mydb.local"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local or internal hostname is forbidden in TiDB mode");

        assertThatThrownBy(() -> DotenvEnvironmentPostProcessor.validateHostSafety("host.docker.internal"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Local or internal hostname is forbidden in TiDB mode");
    }

    // -------------------------------------------------------------------------
    // 5. Local Profile Startup Preservation
    // -------------------------------------------------------------------------

    @Test
    void testLocalProfile_MissingPassword_FailsWithCanonicalError() {
        ConfigurableEnvironment env = new StandardEnvironment();
        DotenvEnvironmentPostProcessor processor = new DotenvEnvironmentPostProcessor();

        // Local mode with missing DB_PASSWORD
        assertThatThrownBy(() -> processor.postProcessEnvironment(env, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Required configuration placeholder 'DB_PASSWORD' is unresolved");
    }
}
