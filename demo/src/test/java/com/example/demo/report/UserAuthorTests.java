package com.example.demo.report;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserAuthorTests {

    private EmbeddedDatabase database;
    private UserAuthorRepository repository;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:schema.sql")
                .build();
        repository = new UserAuthorRepository(database);
    }

    @AfterEach
    void tearDown() {
        database.shutdown();
    }

    private static Authentication authWithEmail(String email) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "none").subject("uid-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (email != null) {
            builder.claim("email", email);
        }
        Jwt jwt = builder.build();
        return new UsernamePasswordAuthenticationToken(jwt, null);
    }

    @Test
    void emailLookupIsCaseInsensitive() {
        repository.save("Dev_Test@Example.com", "林小華");

        assertThat(repository.findAuthor("dev_test@example.com")).contains("林小華");
        assertThat(repository.findAuthor("DEV_TEST@EXAMPLE.COM")).contains("林小華");
        assertThat(repository.findAuthor("other@example.com")).isEmpty();
        assertThat(repository.findAuthor(null)).isEmpty();
    }

    @Test
    void savingAgainReplacesTheMapping() {
        repository.save("a@example.com", "甲");
        repository.save("a@example.com", "乙");

        assertThat(repository.findAuthor("a@example.com")).contains("乙");
    }

    @Test
    void loaderImportsValidEntriesAndSkipsIncompleteOnes() throws Exception {
        String json = """
                [{"email":"a@example.com","author":"甲"},
                 {"email":"","author":"乙"},
                 {"email":"c@example.com"}]
                """;

        int imported = new UserAuthorLoader(repository)
                .load(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(imported).isEqualTo(1);
        assertThat(repository.findAuthor("a@example.com")).contains("甲");
    }

    @Test
    void resolverReadsTheEmailClaimOfTheLoggedInUser() {
        repository.save("dev_test@example.com", "林小華");
        AuthorResolver resolver = new AuthorResolver(repository);

        assertThat(resolver.resolve(authWithEmail("dev_test@example.com"))).contains("林小華");
        assertThat(resolver.resolve(authWithEmail("nobody@example.com"))).isEmpty();
        assertThat(resolver.resolve(authWithEmail(null))).isEmpty();
        assertThat(resolver.resolve(null)).isEmpty();
    }
}
