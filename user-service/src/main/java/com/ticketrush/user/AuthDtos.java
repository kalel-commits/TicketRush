package com.ticketrush.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Locale;

/** Request/response records. Emails are normalized during binding, i.e. before validation and persistence. */
final class AuthDtos {

    private AuthDtos() {
    }

    static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(Locale.ROOT);
    }

    record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72) @MaxUtf8Bytes(72) String password) {
        RegisterRequest {
            email = normalizeEmail(email);
        }
    }

    /** No size validation: bad input always answers 401 INVALID_CREDENTIALS. */
    record LoginRequest(String email, String password) {
        LoginRequest {
            email = normalizeEmail(email);
        }
    }

    record UserView(long id, String email, String role) {
        static UserView of(User u) {
            return new UserView(u.getId(), u.getEmail(), u.getRole().name());
        }
    }

    record TokenResponse(String accessToken, String tokenType, long expiresIn) {
    }
}
