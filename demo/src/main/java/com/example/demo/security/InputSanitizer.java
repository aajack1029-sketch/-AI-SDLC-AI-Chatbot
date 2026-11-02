package com.example.demo.security;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.regex.Pattern;

@Component
public class InputSanitizer {

    private static final Pattern INJECTION_PATTERN = Pattern.compile(
            "\\b(?:ignore\\s+(?:all\\s+)?previous\\s+instructions|system\\s+prompt|you\\s+are\\s+now|"
                    + "override\\s+safety|forget\\s+all\\s+rules|act\\s+as)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    public boolean containsPromptInjection(String userQuery) {
        if (userQuery == null || userQuery.isBlank()) {
            return false;
        }

        String normalizedQuery = Normalizer.normalize(userQuery, Normalizer.Form.NFKC);
        StringBuilder visibleQuery = new StringBuilder(normalizedQuery.length());
        normalizedQuery.codePoints()
                .filter(codePoint -> Character.getType(codePoint) != Character.FORMAT)
                .forEach(visibleQuery::appendCodePoint);

        return INJECTION_PATTERN.matcher(visibleQuery).find();
    }
}
