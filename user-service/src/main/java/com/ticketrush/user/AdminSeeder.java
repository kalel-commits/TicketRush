package com.ticketrush.user;

import com.ticketrush.common.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Creates the admin account from ADMIN_EMAIL / ADMIN_PASSWORD on startup if it doesn't exist. Safe to re-run. */
@Component
public class AdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminSeeder.class);

    private final UserRepository users;
    private final AuthService auth;
    private final String email;
    private final String password;

    AdminSeeder(UserRepository users, AuthService auth,
                @Value("${ticketrush.admin.email:}") String email,
                @Value("${ticketrush.admin.password:}") String password) {
        this.users = users;
        this.auth = auth;
        this.email = AuthDtos.normalizeEmail(email);
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email == null || email.isBlank() || password == null || password.isBlank()) {
            log.info("ADMIN_EMAIL/ADMIN_PASSWORD not set - no admin seeded");
            return;
        }
        if (users.existsByEmail(email)) {
            return;
        }
        try {
            auth.create(email, password, Role.ADMIN);
            log.info("Seeded admin account {}", email);
        } catch (ApiException alreadyCreatedByAnotherInstance) {
            // duplicate-key race between instances: the admin exists, which is all we need
        }
    }
}
