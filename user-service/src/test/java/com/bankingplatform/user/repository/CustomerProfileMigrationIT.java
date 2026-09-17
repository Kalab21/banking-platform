package com.bankingplatform.user.repository;

import com.bankingplatform.user.model.CustomerIdentity;
import com.bankingplatform.user.model.IdentityStatus;
import com.bankingplatform.user.model.Role;
import com.bankingplatform.user.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The onboarding schema, against a real PostgreSQL database.
 *
 * <p>Three things a mocked repository cannot show:
 * <ul>
 *   <li>V3 applies cleanly on top of the earlier migrations;</li>
 *   <li>the entities agree with the migrated schema — the service runs
 *       {@code ddl-auto: validate}, so any drift between a column and its
 *       mapping fails this at context startup, which is how the {@code CHAR}
 *       versus {@code VARCHAR} padding difference would surface;</li>
 *   <li>the profile columns really are nullable, so a row written before
 *       onboarding existed still loads.</li>
 * </ul>
 *
 * <p>Named {@code *IT} and bound to Failsafe, so it runs under {@code mvn
 * verify} and is skipped by a bare {@code mvn test}. Requires a working Docker
 * daemon.
 */
@Testcontainers
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@DisplayName("Customer profile and identity — PostgreSQL integration")
class CustomerProfileMigrationIT {

    @Container
    @SuppressWarnings("resource") // closed by the Testcontainers JUnit extension
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("user_db")
                    .withUsername("bankingadmin")
                    .withPassword("test-only-password");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private UserRepository userRepository;
    @Autowired private CustomerIdentityRepository identityRepository;
    @Autowired private EntityManager entityManager;

    private static User onboardedCustomer(String username) {
        return User.builder()
                .username(username)
                .email(username + "@example.com")
                .password("{bcrypt}$2a$10$storedhash")
                .firstName("Avery")
                .middleName("Quinn")
                .lastName("Sinclair")
                .dateOfBirth(LocalDate.of(1990, 1, 15))
                .phone("2405550148")
                .streetAddress("123 Example Street")
                .addressLine2("Apt 4B")
                .city("Silver Spring")
                .state("MD")
                .postalCode("20910")
                .role(Role.CUSTOMER)
                .build();
    }

    @Test
    @DisplayName("a full profile survives a database round trip unchanged")
    void profileRoundTrips() {
        User saved = userRepository.saveAndFlush(onboardedCustomer("avery.sinclair"));
        entityManager.clear();

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getMiddleName()).isEqualTo("Quinn");
        assertThat(reloaded.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 15));
        assertThat(reloaded.getStreetAddress()).isEqualTo("123 Example Street");
        assertThat(reloaded.getAddressLine2()).isEqualTo("Apt 4B");
        assertThat(reloaded.getCity()).isEqualTo("Silver Spring");
        assertThat(reloaded.getPostalCode()).isEqualTo("20910");
    }

    @Test
    @DisplayName("the two-letter state code comes back without padding")
    void stateIsNotPadded() {
        // The column is VARCHAR(2) rather than CHAR(2) on purpose: CHAR pads to
        // width, and a padded value then fails Hibernate's schema validation
        // against a String mapping.
        User saved = userRepository.saveAndFlush(onboardedCustomer("padding.check"));
        entityManager.clear();

        assertThat(userRepository.findById(saved.getId()).orElseThrow().getState())
                .isEqualTo("MD")
                .hasSize(2);
    }

    @Test
    @DisplayName("a row from before onboarding existed still loads")
    void legacyRowWithoutProfileColumns() {
        // This is what makes V3 safe to run on a populated database: the new
        // columns are nullable, so existing accounts are not invalidated by a
        // migration that has no sensible value to backfill.
        User legacy = User.builder()
                .username("demo.user")
                .email("demo.user@example.com")
                .password("{bcrypt}$2a$10$storedhash")
                .firstName("Demo")
                .lastName("User")
                .role(Role.CUSTOMER)
                .build();

        User saved = userRepository.saveAndFlush(legacy);
        entityManager.clear();

        User reloaded = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getDateOfBirth()).isNull();
        assertThat(reloaded.getStreetAddress()).isNull();
        assertThat(reloaded.getState()).isNull();
        assertThat(identityRepository.findByUserId(saved.getId())).isEmpty();
    }

    @Test
    @DisplayName("an identity record holds four digits and a submitted status")
    void identityRoundTrips() {
        User owner = userRepository.saveAndFlush(onboardedCustomer("identity.owner"));

        identityRepository.saveAndFlush(CustomerIdentity.builder()
                .userId(owner.getId())
                .ssnLast4("6789")
                .status(IdentityStatus.SUBMITTED)
                .build());
        entityManager.clear();

        Optional<CustomerIdentity> reloaded = identityRepository.findByUserId(owner.getId());

        assertThat(reloaded).isPresent().get().satisfies(identity -> {
            assertThat(identity.getSsnLast4()).isEqualTo("6789");
            assertThat(identity.getStatus()).isEqualTo(IdentityStatus.SUBMITTED);
            assertThat(identity.getSubmittedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("one identity record per customer, enforced by the database")
    void identityIsUniquePerUser() {
        User owner = userRepository.saveAndFlush(onboardedCustomer("only.once"));
        identityRepository.saveAndFlush(CustomerIdentity.builder()
                .userId(owner.getId())
                .ssnLast4("6789")
                .status(IdentityStatus.SUBMITTED)
                .build());

        assertThatThrownBy(() -> identityRepository.saveAndFlush(CustomerIdentity.builder()
                .userId(owner.getId())
                .ssnLast4("4321")
                .status(IdentityStatus.SUBMITTED)
                .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the identity table has no column wide enough to hold a full number")
    void schemaCannotStoreAFullSsn() {
        // A structural check rather than a behavioural one: if someone later
        // adds an ssn column to carry the whole number, this fails regardless
        // of what the service does with it.
        @SuppressWarnings("unchecked")
        var columns = (java.util.List<Object[]>) entityManager.createNativeQuery("""
                SELECT column_name, character_maximum_length
                FROM information_schema.columns
                WHERE table_name = 'customer_identity'
                """).getResultList();

        assertThat(columns).isNotEmpty();
        for (Object[] column : columns) {
            String name = String.valueOf(column[0]);
            assertThat(name).as("column name").doesNotContain("ssn_full").doesNotContain("social");
            if (name.startsWith("ssn")) {
                assertThat(((Number) column[1]).intValue())
                        .as("width of %s", name)
                        .isLessThanOrEqualTo(4);
            }
        }
    }

    @Test
    @DisplayName("the users table gained no column for a Social Security number")
    void usersTableHasNoSsnColumn() {
        @SuppressWarnings("unchecked")
        var columns = (java.util.List<String>) entityManager.createNativeQuery("""
                SELECT column_name
                FROM information_schema.columns
                WHERE table_name = 'users'
                """).getResultList();

        assertThat(columns).isNotEmpty();
        assertThat(columns).noneSatisfy(name ->
                assertThat(name).containsAnyOf("ssn", "social", "tax_id"));
    }
}
