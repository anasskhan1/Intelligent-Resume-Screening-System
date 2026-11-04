package com.system.resume.security;

import com.system.resume.model.Role;
import com.system.resume.model.User;
import com.system.resume.repository.RoleRepository;
import com.system.resume.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Idempotent startup initializer that provisions an initial platform administrator account
 * if configured via environment properties (INITIAL_ADMIN_EMAIL and INITIAL_ADMIN_PASSWORD).
 *
 * Security Requirements:
 * - Public registration remains strictly limited to ROLE_RECRUITER.
 * - If the administrator account already exists, existing credentials and roles are never overwritten.
 * - Administrator credentials are never logged, exposed, or committed to source control.
 */
@Component
@Order(1)
public class AdminUserInitializer implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminUserInitializer.class);
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.bootstrap.admin.email:${INITIAL_ADMIN_EMAIL:}}")
    private String adminEmail;

    @Value("${app.bootstrap.admin.password:${INITIAL_ADMIN_PASSWORD:}}")
    private String adminPassword;

    @Value("${app.bootstrap.admin.first-name:${INITIAL_ADMIN_FIRST_NAME:Admin}}")
    private String adminFirstName;

    @Value("${app.bootstrap.admin.last-name:${INITIAL_ADMIN_LAST_NAME:System}}")
    private String adminLastName;

    @Autowired
    public AdminUserInitializer(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank()) {
            logger.info("Admin bootstrap: No INITIAL_ADMIN_EMAIL configured; skipping automatic admin provisioning.");
            return;
        }

        String normalizedEmail = adminEmail.trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(normalizedEmail)) {
            logger.info("Admin bootstrap: Administrator account '{}' already exists. Preserving existing account without modification.", normalizedEmail);
            return;
        }

        if (adminPassword == null || adminPassword.isBlank()) {
            logger.warn("Admin bootstrap: INITIAL_ADMIN_EMAIL is configured for '{}', but INITIAL_ADMIN_PASSWORD is empty. Skipping admin provisioning.", normalizedEmail);
            return;
        }

        if (adminPassword.length() < MIN_PASSWORD_LENGTH) {
            logger.warn("Admin bootstrap: INITIAL_ADMIN_PASSWORD for '{}' is shorter than {} characters. Skipping admin provisioning.", normalizedEmail, MIN_PASSWORD_LENGTH);
            return;
        }

        if (adminPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            logger.warn("Admin bootstrap: INITIAL_ADMIN_PASSWORD for '{}' exceeds {} UTF-8 bytes. Skipping admin provisioning.", normalizedEmail, MAX_PASSWORD_BYTES);
            return;
        }

        Role adminRole = roleRepository.findByName("ROLE_ADMIN")
                .orElseGet(() -> {
                    logger.warn("Role 'ROLE_ADMIN' not found during admin bootstrap; creating role record.");
                    return roleRepository.save(new Role("ROLE_ADMIN", "System administrator with unrestricted platform and tenant access"));
                });

        Role recruiterRole = roleRepository.findByName("ROLE_RECRUITER")
                .orElseGet(() -> roleRepository.save(new Role("ROLE_RECRUITER", "Talent acquisition specialist managing job postings, candidate evaluations, and screenings")));

        User adminUser = new User(
                normalizedEmail,
                passwordEncoder.encode(adminPassword),
                adminFirstName != null && !adminFirstName.isBlank() ? adminFirstName.trim() : "Admin",
                adminLastName != null && !adminLastName.isBlank() ? adminLastName.trim() : "System",
                true
        );

        adminUser.getRoles().add(adminRole);
        adminUser.getRoles().add(recruiterRole);

        userRepository.save(adminUser);
        logger.info("Admin bootstrap: Successfully provisioned initial administrator account with email '{}'.", normalizedEmail);
    }
}

