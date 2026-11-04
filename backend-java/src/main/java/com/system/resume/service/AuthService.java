package com.system.resume.service;

import com.system.resume.dto.AuthResponseDto;
import com.system.resume.dto.LoginRequestDto;
import com.system.resume.dto.LoginResult;
import com.system.resume.dto.RefreshTokenResult;
import com.system.resume.dto.RegisterRequestDto;
import com.system.resume.dto.UserResponseDto;
import com.system.resume.exception.EmailAlreadyExistsException;
import com.system.resume.exception.InvalidCredentialsException;
import com.system.resume.model.Role;
import com.system.resume.model.User;
import com.system.resume.repository.RoleRepository;
import com.system.resume.repository.UserRepository;
import com.system.resume.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Service managing user registration, credential authentication,
 * BCrypt password hashing, and role assignment.
 */
@Service
@Transactional(readOnly = true)
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    public static final String DEFAULT_REGISTRATION_ROLE = "ROLE_RECRUITER";
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_BYTES = 72;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Autowired
    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            RefreshTokenService refreshTokenService
    ) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
    }

    public AuthService(
            UserRepository userRepository,
            RoleRepository roleRepository,
            PasswordEncoder passwordEncoder
    ) {
        this(userRepository, roleRepository, passwordEncoder, null, null);
    }

    /**
     * Registers a new user account with default ROLE_RECRUITER and active status.
     * Enforces unique email normalization and BCrypt password encryption.
     * Passwords are never trimmed or normalized.
     *
     * @param request the registration request DTO
     * @return safe UserResponseDto without password hashes or sensitive tokens
     */
    @Transactional
    public UserResponseDto register(RegisterRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("Registration request cannot be null.");
        }

        validatePasswordBytes(request.password());

        String normalizedEmail = normalizeEmail(request.email());

        if (userRepository.existsByEmail(normalizedEmail)) {
            logger.warn("Registration rejected: email '{}' is already in use", normalizedEmail);
            throw new EmailAlreadyExistsException("Email '" + normalizedEmail + "' is already registered.");
        }

        Role recruiterRole = roleRepository.findByName(DEFAULT_REGISTRATION_ROLE)
                .orElseGet(() -> {
                    logger.warn("Role '{}' not found in database; creating default entry", DEFAULT_REGISTRATION_ROLE);
                    return roleRepository.save(new Role(
                            DEFAULT_REGISTRATION_ROLE,
                            "Recruiter with access to job descriptions, candidate profiles, and screening executions"
                    ));
                });

        // Exact password preserved without trimming
        String hashedPassword = passwordEncoder.encode(request.password());

        User newUser = new User();
        newUser.setEmail(normalizedEmail);
        newUser.setPasswordHash(hashedPassword);
        newUser.setFirstName(request.firstName().trim());
        newUser.setLastName(request.lastName().trim());
        newUser.setIsActive(true);
        newUser.addRole(recruiterRole);

        User savedUser = userRepository.save(newUser);
        logger.info("Successfully registered user ID: {} with role '{}'", savedUser.getId(), DEFAULT_REGISTRATION_ROLE);

        return UserResponseDto.fromEntity(savedUser);
    }

    /**
     * Authenticates a user against their stored BCrypt password hash.
     * Protects against account enumeration by returning identical generic error messages
     * for missing users, incorrect passwords, and inactive accounts, while recording
     * appropriate internal audit log entries without logging passwords.
     *
     * @param request the login request DTO containing email and raw password
     * @return validated active User entity
     */
    public User authenticate(LoginRequestDto request) {
        if (request == null) {
            throw new IllegalArgumentException("Login request cannot be null.");
        }

        if (request.password() == null) {
            throw new InvalidCredentialsException("Invalid email or password.");
        }

        // Fast rejection for passwords exceeding BCrypt's maximum 72-byte capacity
        if (request.password().getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            logger.warn("Login failed: supplied password exceeds maximum 72-byte length");
            throw new InvalidCredentialsException("Invalid email or password.");
        }

        String normalizedEmail = normalizeEmail(request.email());

        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> {
                    logger.warn("Login failed: account not found for email '{}'", normalizedEmail);
                    return new InvalidCredentialsException("Invalid email or password.");
                });

        // Exact password passed to matches without trimming
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            logger.warn("Login failed: password mismatch for user ID: {}", user.getId());
            throw new InvalidCredentialsException("Invalid email or password.");
        }

        // Inactive account check returns generic message to prevent enumeration
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            logger.warn("Login failed: account ID: {} is deactivated", user.getId());
            throw new InvalidCredentialsException("Invalid email or password.");
        }

        logger.info("User ID: {} successfully authenticated", user.getId());
        return user;
    }

    /**
     * Authenticates user and issues access token and refresh token.
     *
     * @param request the login request
     * @param clientIp client IP address
     * @return LoginResult containing AuthResponseDto and raw refresh token
     */
    @Transactional
    public LoginResult login(LoginRequestDto request, String clientIp) {
        User user = authenticate(request);

        String accessToken = (jwtService != null) ? jwtService.generateAccessToken(user) : null;
        String rawRefreshToken = null;

        if (refreshTokenService != null) {
            RefreshTokenResult refreshResult = refreshTokenService.createRefreshToken(user, clientIp);
            rawRefreshToken = refreshResult.rawRefreshToken();
        }

        UserResponseDto userDto = UserResponseDto.fromEntity(user);
        AuthResponseDto authResponse = new AuthResponseDto(accessToken, userDto);
        return new LoginResult(authResponse, rawRefreshToken);
    }

    /**
     * Compatibility login method returning UserResponseDto.
     *
     * @param request the login request
     * @return safe UserResponseDto
     */
    public UserResponseDto login(LoginRequestDto request) {
        User user = authenticate(request);
        return UserResponseDto.fromEntity(user);
    }

    /**
     * Enforces the 72-byte BCrypt input constraint to prevent silent truncation.
     */
    public static void validatePasswordBytes(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters long.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("Password exceeds maximum supported length of 72 bytes for BCrypt hashing.");
        }
    }

    /**
     * Normalizes email consistently by trimming whitespace and converting to lowercase.
     */
    public static String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}

