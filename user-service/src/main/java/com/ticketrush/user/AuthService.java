package com.ticketrush.user;

import com.ticketrush.common.error.ApiException;
import com.ticketrush.common.error.ErrorCode;
import com.ticketrush.common.security.TokenClaims;
import com.ticketrush.user.AuthDtos.LoginRequest;
import com.ticketrush.user.AuthDtos.RegisterRequest;
import com.ticketrush.user.AuthDtos.TokenResponse;
import com.ticketrush.user.AuthDtos.UserView;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;

@Service
public class AuthService {

    static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository users;
    private final UserTokenIssuer tokens;
    private final Clock clock;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder(10);
    /** Compared against when the email is unknown, so both failure paths cost one BCrypt check. */
    private final String dummyHash = encoder.encode("timing-equalizer-password");

    AuthService(UserRepository users, UserTokenIssuer tokens, Clock clock) {
        this.users = users;
        this.tokens = tokens;
        this.clock = clock;
    }

    public UserView register(RegisterRequest req) {
        return UserView.of(create(req.email(), req.password(), Role.USER));
    }

    /** Insert and let the unique index decide (no check-then-insert race). */
    User create(String email, String password, Role role) {
        try {
            return users.saveAndFlush(new User(email, encoder.encode(password), role, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.EMAIL_TAKEN, "An account with this email already exists");
        }
    }

    public TokenResponse login(LoginRequest req) {
        String password = req.password();
        if (req.email() == null || password == null || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw invalidCredentials();
        }
        Optional<User> user = users.findByEmail(req.email());
        boolean matches = encoder.matches(password, user.map(User::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !matches) {
            throw invalidCredentials();
        }
        return new TokenResponse(tokens.issue(user.get()), "Bearer", TokenClaims.USER_TOKEN_TTL.toSeconds());
    }

    public UserView me(long userId) {
        return users.findById(userId).map(UserView::of)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "User not found"));
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.INVALID_CREDENTIALS, "Invalid email or password");
    }
}
