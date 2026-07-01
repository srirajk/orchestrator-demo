package com.openwolf.iam.service;

import com.openwolf.iam.repository.PrincipalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs at startup to replace the {@code SEED_REPLACE_ME} placeholder passwords
 * in the SQL seed data with real BCrypt hashes.
 * <p>
 * This runs AFTER Flyway migrations and BEFORE the app serves any requests.
 * It is idempotent — if the password is already a real hash (not the placeholder), it skips.
 * </p>
 */
@Component
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final String PLACEHOLDER = "SEED_REPLACE_ME";

    private final PrincipalRepository principalRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${iam.seed.enabled:true}")
    private boolean seedEnabled;

    @Value("${iam.admin.password:Meridian@2024}")
    private String adminPassword;

    public DataSeeder(PrincipalRepository principalRepository, PasswordEncoder passwordEncoder) {
        this.principalRepository = principalRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled) {
            log.info("DataSeeder is disabled (iam.seed.enabled=false) — skipping");
            return;
        }

        log.info("DataSeeder: checking for placeholder passwords...");
        int updated = 0;

        for (var principal : principalRepository.findByPasswordHashPlaceholder(PLACEHOLDER)) {
            String newHash = passwordEncoder.encode(adminPassword);
            principal.setPasswordHash(newHash);
            principalRepository.save(principal);
            log.info("DataSeeder: updated password hash for principal id={} username={}",
                    principal.getId(), principal.getUsername());
            updated++;
        }

        if (updated == 0) {
            log.info("DataSeeder: no placeholder passwords found — seed already applied");
        } else {
            log.info("DataSeeder: updated {} principal password(s)", updated);
        }
    }
}
