package com.example.demo.report;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Optional;

/** 登入帳號 (email) 與日報作者姓名的對應。 */
@Repository
public class UserAuthorRepository {

    private final JdbcClient jdbc;

    public UserAuthorRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<String> findAuthor(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT author FROM user_authors WHERE email = :email")
                .param("email", normalize(email))
                .query(String.class)
                .optional();
    }

    @Transactional
    public void save(String email, String author) {
        jdbc.sql("DELETE FROM user_authors WHERE email = :email")
                .param("email", normalize(email)).update();
        jdbc.sql("INSERT INTO user_authors (email, author) VALUES (:email, :author)")
                .param("email", normalize(email))
                .param("author", author.trim())
                .update();
    }

    private static String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
