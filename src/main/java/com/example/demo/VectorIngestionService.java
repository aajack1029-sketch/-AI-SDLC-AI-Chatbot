package com.example.demo;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class VectorIngestionService implements CommandLineRunner {

    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;
    private final boolean ingestionEnabled;
    private final int ingestionLimit;

    public VectorIngestionService(
            VectorStore vectorStore,
            ObjectMapper objectMapper,
            @Value("${app.ingestion.enabled:true}") boolean ingestionEnabled,
            @Value("${app.ingestion.limit:0}") int ingestionLimit) {
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
        this.ingestionEnabled = ingestionEnabled;
        this.ingestionLimit = ingestionLimit;
    }

    @Override
    public void run(String... args) throws Exception {
        if (ingestionLimit < 0) {
            throw new IllegalStateException("app.ingestion.limit must be zero or greater");
        }

        if (!ingestionEnabled) {
            log.info("啟動時資料匯入已停用，保留 Qdrant 中現有資料");
            return;
        }

        log.info("開始檢查並寫入 Mock 每日工作日報資料至 Qdrant...");
        
        InputStream inputStream = getClass().getResourceAsStream("/Data/mock_daily_reports.json");
        if (inputStream == null) {
            log.warn("未找到 /Data/mock_daily_reports.json，跳過向量資料匯入");
            return;
        }

        List<Map<String, Object>> reports = objectMapper.readValue(inputStream, new TypeReference<>() {});
        if (ingestionLimit > 0 && reports.size() > ingestionLimit) {
            reports = reports.subList(0, ingestionLimit);
            log.info("本次匯入限制為 {} 筆日報", ingestionLimit);
        }

        List<Document> documents = reports.stream().map(report -> {
            String reportId = String.valueOf(report.getOrDefault("report_id", ""));
            
            String content = String.format(
                "日報編號: %s\n日期: %s\n姓名: %s\n職稱: %s\n專案: %s\n完成任務: %s\n遇到的問題: %s\n明日計畫: %s",
                getOrDefault(report, "report_id"),
                getOrDefault(report, "date"),
                getOrDefault(report, "author"),
                getOrDefault(report, "role"),
                getOrDefault(report, "project"),
                getOrDefault(report, "tasks_completed"),
                getOrDefault(report, "issues_and_blockers"),
                getOrDefault(report, "tomorrow_plan")
            );

            // 安全建構 Metadata (避免 NullPointerException)
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("report_id", getOrDefault(report, "report_id"));
            metadata.put("date", getOrDefault(report, "date"));
            metadata.put("author", getOrDefault(report, "author"));
            metadata.put("project", getOrDefault(report, "project"));

            // 帶入固定 ID（如 report_id），避免 Qdrant 重複寫入相同資料
            String documentUuid = reportId.isEmpty() 
                    ? java.util.UUID.randomUUID().toString() 
                    : java.util.UUID.nameUUIDFromBytes(reportId.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

            return new Document(documentUuid, content, metadata);
        }).collect(Collectors.toList());

        for (Document document : documents) {
            vectorStore.add(List.of(document));
        }
        log.info("成功寫入 {} 筆工作日報至 Qdrant 向量資料庫！", documents.size());
    }

    private String getOrDefault(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "無";
    }
}