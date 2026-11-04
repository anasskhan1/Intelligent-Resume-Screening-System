package com.system.resume.service;

import com.system.resume.config.SecurityConfig;
import com.system.resume.dto.LoginRequestDto;
import com.system.resume.dto.RegisterRequestDto;
import com.system.resume.dto.UserResponseDto;
import com.system.resume.exception.AccountInactiveException;
import com.system.resume.exception.EmailAlreadyExistsException;
import com.system.resume.exception.InvalidCredentialsException;
import com.system.resume.model.Role;
import com.system.resume.model.User;
import com.system.resume.repository.RoleRepository;
import com.system.resume.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    private PasswordEncoder passwordEncoder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        // Use agreed work factor 12
        passwordEncoder = new BCryptPasswordEncoder(SecurityConfig.BCRYPT_STRENGTH);
        authService = new AuthService(userRepository, roleRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Should successfully register user, hash password with BCrypt (strength 12), and assign ROLE_RECRUITER")
    void testRegister_Success_EncodesPasswordWithBCryptAndAssignsRecruiterRole() {
        RegisterRequestDto request = new RegisterRequestDto(
                "Jane.Doe@Example.COM ",
                "SecureP@ssw0rd123",
                " Jane ",
                " Doe "
        );

        Role recruiterRole = new Role(1L, "ROLE_RECRUITER", "Recruiter role", LocalDateTime.now());
        when(userRepository.existsByEmail("jane.doe@example.com")).thenReturn(false);
        when(roleRepository.findByName("ROLE_RECRUITER")).thenReturn(Optional.of(recruiterRole));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(101L);
            u.setCreatedAt(LocalDateTime.now());
            return u;
        });

        UserResponseDto response = authService.register(request);

        assertNotNull(response);
        assertEquals(101L, response.id());
        assertEquals("jane.doe@example.com", response.email());
        assertEquals("Jane", response.firstName());
        assertEquals("Doe", response.lastName());
        assertEquals("Jane Doe", response.fullName());
        assertTrue(response.isActive());
        assertEquals(Set.of("ROLE_RECRUITER"), response.roles());

        // Verify password was hashed with BCrypt strength 12 and plain-text was never stored
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User savedUser = captor.getValue();

        assertNotEquals("SecureP@ssw0rd123", savedUser.getPasswordHash());
        assertTrue(savedUser.getPasswordHash().startsWith("$2a$12$") || savedUser.getPasswordHash().startsWith("$2b$12$"),
                "Stored hash must be generated with work factor 12: " + savedUser.getPasswordHash());
        assertTrue(passwordEncoder.matches("SecureP@ssw0rd123", savedUser.getPasswordHash()));
    }

    @Test
    @DisplayName("Should preserve exact password with leading/trailing spaces during registration and login")
    void testRegisterAndLogin_PreservesExactPasswordSpaces() {
        String spacedPassword = "   ExactSpacesPassword!   ";
        RegisterRequestDto regRequest = new RegisterRequestDto(
                "spaced@example.com",
                spacedPassword,
                "Space",
                "User"
        );

        Role recruiterRole = new Role(1L, "ROLE_RECRUITER", "Recruiter", LocalDateTime.now());
        when(userRepository.existsByEmail("spaced@example.com")).thenReturn(false);
        when(roleRepository.findByName("ROLE_RECRUITER")).thenReturn(Optional.of(recruiterRole));

        User capturedUser = new User();
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User u = invocation.getArgument(0);
            capturedUser.setEmail(u.getEmail());
            capturedUser.setPasswordHash(u.getPasswordHash());
            capturedUser.setIsActive(true);
            return u;
        });

        authService.register(regRequest);

        // Exact password must match the hashed value
        assertTrue(passwordEncoder.matches(spacedPassword, capturedUser.getPasswordHash()));
        // Trimmed password must NOT match
        assertFalse(passwordEncoder.matches("ExactSpacesPassword!", capturedUser.getPasswordHash()));

        // Login with exact spaced password must succeed
        when(userRepository.findByEmail("spaced@example.com")).thenReturn(Optional.of(capturedUser));
        UserResponseDto loginResponse = authService.login(new LoginRequestDto("spaced@example.com", spacedPassword));
        assertNotNull(loginResponse);

        // Login with trimmed password must fail with InvalidCredentialsException
        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequestDto("spaced@example.com", "ExactSpacesPassword!")));
    }

    @Test
    @DisplayName("Should support maximum 72-byte password and reject passwords exceeding 72 bytes")
    void testPassword_Maximum72ByteLimitEnforcement() {
        // Exactly 72 ASCII bytes
        String maxValidPassword = "A".repeat(72);
        assertDoesNotThrow(() -> AuthService.validatePasswordBytes(maxValidPassword));

        // 73 bytes exceeds BCrypt maximum
        String tooLongPassword = "A".repeat(73);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> AuthService.validatePasswordBytes(tooLongPassword));
        assertTrue(ex.getMessage().contains("72 bytes"));

        // Multi-byte UTF-8 testing: each character is 3 bytes in UTF-8
        // 24 characters * 3 bytes = 72 bytes (allowed)
        String multiByte72 = "\u20AC".repeat(24);
        assertDoesNotThrow(() -> AuthService.validatePasswordBytes(multiByte72));

        // 25 characters * 3 bytes = 75 bytes (rejected)
        String multiByte75 = "\u20AC".repeat(25);
        assertThrows(IllegalArgumentException.class,
                () -> AuthService.validatePasswordBytes(multiByte75));
    }

    @Test
    @DisplayName("Should reject duplicate registration email with EmailAlreadyExistsException")
    void testRegister_DuplicateEmail_ThrowsEmailAlreadyExistsException() {
        RegisterRequestDto request = new RegisterRequestDto(
                "existing@example.com",
                "Password123!",
                "John",
                "Smith"
        );

        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThrows(EmailAlreadyExistsException.class, () -> authService.register(request));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Should normalize email by trimming and converting to lowercase")
    void testRegister_EmailNormalization() {
        RegisterRequestDto request = new RegisterRequestDto(
                "   USER.NAME+Tag@DOMAIN.COM  ",
                "Password123!",
                "User",
                "Name"
        );

        Role recruiterRole = new Role(1L, "ROLE_RECRUITER", "Recruiter", LocalDateTime.now());
        when(userRepository.existsByEmail("user.name+tag@domain.com")).thenReturn(false);
        when(roleRepository.findByName("ROLE_RECRUITER")).thenReturn(Optional.of(recruiterRole));
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.register(request);

        verify(userRepository).existsByEmail("user.name+tag@domain.com");
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals("user.name+tag@domain.com", captor.getValue().getEmail());
    }

    @Test
    @DisplayName("Should successfully authenticate valid credentials and return user response DTO")
    void testLogin_Success() {
        LoginRequestDto request = new LoginRequestDto("recruiter@example.com", "CorrectPassword123");

        String hashedPassword = passwordEncoder.encode("CorrectPassword123");
        User user = new User("recruiter@example.com", hashedPassword, "Alice", "Wong", true);
        user.setId(50L);
        user.setCreatedAt(LocalDateTime.now());
        user.addRole(new Role(1L, "ROLE_RECRUITER", "Recruiter", LocalDateTime.now()));

        when(userRepository.findByEmail("recruiter@example.com")).thenReturn(Optional.of(user));

        UserResponseDto response = authService.login(request);

        assertNotNull(response);
        assertEquals(50L, response.id());
        assertEquals("recruiter@example.com", response.email());
        assertEquals("Alice Wong", response.fullName());
        assertTrue(response.isActive());
        assertTrue(response.roles().contains("ROLE_RECRUITER"));
    }

    @Test
    @DisplayName("Should reject login with incorrect password with InvalidCredentialsException")
    void testLogin_IncorrectPassword_ThrowsInvalidCredentialsException() {
        LoginRequestDto request = new LoginRequestDto("recruiter@example.com", "WrongPassword");

        String hashedPassword = passwordEncoder.encode("CorrectPassword123");
        User user = new User("recruiter@example.com", hashedPassword, "Alice", "Wong", true);

        when(userRepository.findByEmail("recruiter@example.com")).thenReturn(Optional.of(user));

        assertThrows(InvalidCredentialsException.class, () -> authService.login(request));
    }

    @Test
    @DisplayName("Should reject login for non-existent email with InvalidCredentialsException")
    void testLogin_NonExistentEmail_ThrowsInvalidCredentialsException() {
        LoginRequestDto request = new LoginRequestDto("nonexistent@example.com", "AnyPassword");

        when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () -> authService.login(request));
    }

    @Test
    @DisplayName("Should reject login for inactive user account with generic InvalidCredentialsException to prevent enumeration")
    void testLogin_InactiveAccount_ThrowsInvalidCredentialsException() {
        LoginRequestDto request = new LoginRequestDto("inactive@example.com", "ValidPassword123");

        String hashedPassword = passwordEncoder.encode("ValidPassword123");
        User inactiveUser = new User("inactive@example.com", hashedPassword, "Bob", "Inactive", false);
        inactiveUser.setId(88L);

        when(userRepository.findByEmail("inactive@example.com")).thenReturn(Optional.of(inactiveUser));

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class, () -> authService.login(request));
        assertEquals("Invalid email or password.", ex.getMessage());
    }
}

