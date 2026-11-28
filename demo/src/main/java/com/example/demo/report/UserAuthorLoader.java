package com.example.demo.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** 啟動時把 Data/user_authors.json (登入帳號 → 作者姓名) 匯入 SQL；檔案不存在則略過。 */
@Slf4j
@Component
public class UserAuthorLoader implements CommandLineRunner {

    private final UserAuthorRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public UserAuthorLoader(UserAuthorRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(String... args) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/Data/user_authors.json")) {
            if (in == null) {
                log.info("沒有 /Data/user_authors.json，略過登入帳號與作者的對應匯入");
                return;
            }
            int count = load(in);
            log.info("已匯入 {} 筆登入帳號與作者姓名的對應", count);
        }
    }

    int load(InputStream json) throws Exception {
        List<Map<String, String>> entries = objectMapper.readValue(json, new TypeReference<>() {});
        int count = 0;
        for (Map<String, String> entry : entries) {
            String email = entry.get("email");
            String author = entry.get("author");
            if (email == null || email.isBlank() || author == null || author.isBlank()) {
                log.warn("略過不完整的帳號對應：{}", entry);
                continue;
            }
            repository.save(email, author);
            count++;
        }
        return count;
    }
}
