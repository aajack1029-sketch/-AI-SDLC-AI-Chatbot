package com.example.demo.report;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 由登入者的 Firebase email 找出對應的日報作者姓名。找不到對應時回傳空值。 */
@Component
public class AuthorResolver {

    private final UserAuthorRepository repository;

    public AuthorResolver(UserAuthorRepository repository) {
        this.repository = repository;
    }

    public Optional<String> resolve(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return repository.findAuthor(jwt.getClaimAsString("email"));
    }
}
