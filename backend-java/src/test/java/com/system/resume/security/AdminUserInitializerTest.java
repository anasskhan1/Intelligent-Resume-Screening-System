package com.system.resume.security;

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
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminUserInitializerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private AdminUserInitializer initializer;

    @BeforeEach
    void setUp() {
        initializer = new AdminUserInitializer(userRepository, roleRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Run does nothing when INITIAL_ADMIN_EMAIL is not configured")
    void testRun_WhenEmailEmpty_DoesNothing() {
        ReflectionTestUtils.setField(initializer, "adminEmail", "");
        ReflectionTestUtils.setField(initializer, "adminPassword", "secret1234");

        initializer.run(new DefaultApplicationArguments());

        verify(userRepository, never()).existsByEmail(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Run preserves existing administrator and never resets credentials")
    void testRun_WhenAdminAlreadyExists_PreservesAccount() {
        ReflectionTestUtils.setField(initializer, "adminEmail", "admin@resumescreening.local");
        ReflectionTestUtils.setField(initializer, "adminPassword", "newSecret1234");

        when(userRepository.existsByEmail("admin@resumescreening.local")).thenReturn(true);

        initializer.run(new DefaultApplicationArguments());

        verify(userRepository, times(1)).existsByEmail("admin@resumescreening.local");
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Run does nothing when INITIAL_ADMIN_PASSWORD is blank")
    void testRun_WhenPasswordEmpty_DoesNothing() {
        ReflectionTestUtils.setField(initializer, "adminEmail", "admin@resumescreening.local");
        ReflectionTestUtils.setField(initializer, "adminPassword", "   ");

        when(userRepository.existsByEmail("admin@resumescreening.local")).thenReturn(false);

        initializer.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Run does nothing when INITIAL_ADMIN_PASSWORD is shorter than 8 characters")
    void testRun_WhenPasswordTooShort_DoesNothing() {
        ReflectionTestUtils.setField(initializer, "adminEmail", "admin@resumescreening.local");
        ReflectionTestUtils.setField(initializer, "adminPassword", "short");

        when(userRepository.existsByEmail("admin@resumescreening.local")).thenReturn(false);

        initializer.run(new DefaultApplicationArguments());

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("Run successfully provisions initial administrator when credentials are valid and user does not exist")
    void testRun_WhenValidCredentials_ProvisionsAdmin() {
        ReflectionTestUtils.setField(initializer, "adminEmail", "Admin@ResumeScreening.LOCAL ");
        ReflectionTestUtils.setField(initializer, "adminPassword", "AdminSecurePassword123!");
        ReflectionTestUtils.setField(initializer, "adminFirstName", "Super");
        ReflectionTestUtils.setField(initializer, "adminLastName", "Admin");

        when(userRepository.existsByEmail("admin@resumescreening.local")).thenReturn(false);
        when(passwordEncoder.encode("AdminSecurePassword123!")).thenReturn("$2a$10$hashedAdminPassword");

        Role adminRole = new Role(1L, "ROLE_ADMIN", "Admin role", null);
        Role recruiterRole = new Role(2L, "ROLE_RECRUITER", "Recruiter role", null);
        when(roleRepository.findByName("ROLE_ADMIN")).thenReturn(Optional.of(adminRole));
        when(roleRepository.findByName("ROLE_RECRUITER")).thenReturn(Optional.of(recruiterRole));

        initializer.run(new DefaultApplicationArguments());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());

        User savedUser = userCaptor.getValue();
        assertEquals("admin@resumescreening.local", savedUser.getEmail());
        assertEquals("$2a$10$hashedAdminPassword", savedUser.getPasswordHash());
        assertEquals("Super", savedUser.getFirstName());
        assertEquals("Admin", savedUser.getLastName());
        assertTrue(savedUser.getIsActive());
        assertTrue(savedUser.getRoles().contains(adminRole));
        assertTrue(savedUser.getRoles().contains(recruiterRole));
    }
}

