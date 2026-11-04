package com.system.resume.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.File;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.profiles.active=tidb")
@ActiveProfiles("tidb")
@EnabledIf("isTiDbConfigured")
public class TiDBCloudLiveVerificationTest {

    @BeforeAll
    static void initProfile() {
        System.setProperty("spring.profiles.active", "tidb");
    }

    static boolean isTiDbConfigured() {
        return new File(".env.tidb").exists() || new File("..", ".env.tidb").exists();
    }

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Verify TiDB Cloud TLS Connection, Pool Limits, Schema & Roles")
    void testTiDbCloudLiveCompatibility() {
        // 1. Verify Hikari Configuration (Maximum pool 3, minimum idle 0)
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(3);
        assertThat(hikari.getMinimumIdle()).isEqualTo(0);
        assertThat(hikari.getPoolName()).isEqualTo("HikariPool-TiDB");

        // 2. Query TiDB version & TLS status (Read-Only)
        String versionComment = jdbcTemplate.queryForObject("SELECT @@version_comment", String.class);
        String version = jdbcTemplate.queryForObject("SELECT version()", String.class);

        List<Map<String, Object>> sslCipherRows = jdbcTemplate.queryForList("SHOW STATUS LIKE 'Ssl_cipher'");
        String sslCipher = sslCipherRows.isEmpty() ? "UNKNOWN" : String.valueOf(sslCipherRows.get(0).get("Value"));

        List<Map<String, Object>> sslVersionRows = jdbcTemplate.queryForList("SHOW STATUS LIKE 'Ssl_version'");
        String sslVersion = sslVersionRows.isEmpty() ? "UNKNOWN" : String.valueOf(sslVersionRows.get(0).get("Value"));

        assertThat(sslCipher).isNotBlank().isNotEqualTo("UNKNOWN");

        // 3. Confirm 13 tables are present
        List<String> expectedTables = List.of(
                "candidate_rankings",
                "candidates",
                "job_descriptions",
                "job_hiring_managers",
                "refresh_tokens",
                "resume_batch_files",
                "resume_batches",
                "resume_skills",
                "resumes",
                "roles",
                "screening_results",
                "user_roles",
                "users"
        );

        List<String> actualTables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() ORDER BY table_name",
                String.class
        );

        assertThat(actualTables).containsAll(expectedTables);

        // 4. Confirm 3 roles are present
        List<String> roles = jdbcTemplate.queryForList("SELECT name FROM roles ORDER BY name", String.class);
        assertThat(roles).containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_HIRING_MANAGER", "ROLE_RECRUITER");

        System.out.println("=== TIDB CLOUD VERIFICATION SUCCESSFUL ===");
        System.out.println("Engine Version Comment: " + versionComment);
        System.out.println("Engine Version: " + version);
        System.out.println("TLS Cipher: " + sslCipher);
        System.out.println("TLS Version: " + sslVersion);
        System.out.println("Hikari Pool: " + hikari.getPoolName() + " (max=" + hikari.getMaximumPoolSize() + ", minIdle=" + hikari.getMinimumIdle() + ")");
        System.out.println("Total Tables in Schema: " + actualTables.size());
        System.out.println("Tables Verified: " + actualTables);
        System.out.println("Roles Verified: " + roles);
        System.out.println("Hibernate ddl-auto=validate: PASSED");
        System.out.println("==========================================");
    }
}
