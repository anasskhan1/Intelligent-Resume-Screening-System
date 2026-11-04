package com.system.resume.dto;

import com.system.resume.model.Role;
import com.system.resume.model.User;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Data Transfer Object for user profile and authentication responses.
 * Never exposes password hashes or security token secrets.
 */
public record UserResponseDto(
        Long id,
        String email,
        String firstName,
        String lastName,
        String fullName,
        Boolean isActive,
        Set<String> roles,
        LocalDateTime createdAt
) {
    public static UserResponseDto fromEntity(User user) {
        if (user == null) {
            return null;
        }

        Set<String> roleNames = user.getRoles() != null
                ? user.getRoles().stream()
                        .map(Role::getName)
                        .collect(Collectors.toCollection(TreeSet::new))
                : Set.of();

        return new UserResponseDto(
                user.getId(),
                user.getEmail(),
                user.getFirstName(),
                user.getLastName(),
                user.getFullName(),
                user.getIsActive(),
                roleNames,
                user.getCreatedAt()
        );
    }
}

