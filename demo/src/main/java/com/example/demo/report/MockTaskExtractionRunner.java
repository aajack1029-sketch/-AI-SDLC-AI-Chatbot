package com.example.demo.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 一次性工具：用 Gemini 對 mock_daily_reports.json 的每筆日報抽取任務，寫成 mock_daily_tasks.json。
 * 只有 app.tools.extract-mock-tasks=true 時才會執行；可中斷後重跑，已抽取的日報會被略過。
 * 執行時請同時設 APP_INGESTION_ENABLED=false，避免重複寫入 Qdrant。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.tools.extract-mock-tasks", havingValue = "true")
public class MockTaskExtractionRunner implements CommandLineRunner {

    private static final int MAX_ATTEMPTS = 3;

    private final TaskExtractionService extractionService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final File outputFile;
    private final long delayMillis;

    public MockTaskExtractionRunner(
            TaskExtractionService extractionService,
            @Value("${app.tools.extract-output:src/main/resources/Data/mock_daily_tasks.json}") String outputPath,
            @Value("${app.tools.extract-delay-ms:4500}") long delayMillis) {
        this.extractionService = extractionService;
        this.outputFile = new File(outputPath);
        this.delayMillis = delayMillis;
    }

    @Override
    public void run(String... args) throws Exception {
        List<Map<String, Object>> reports;
        try (InputStream in = getClass().getResourceAsStream("/Data/mock_daily_reports.json")) {
            if (in == null) {
                throw new IllegalStateException("找不到 /Data/mock_daily_reports.json");
            }
            reports = objectMapper.readValue(in, new TypeReference<>() {});
        }

        Map<String, List<Map<String, Object>>> done = new LinkedHashMap<>();
        if (outputFile.exists()) {
            List<Map<String, Object>> existing =
                    objectMapper.readValue(outputFile, new TypeReference<>() {});
            for (Map<String, Object> entry : existing) {
                done.put(String.valueOf(entry.get("report_id")), taskMaps(entry));
            }
            log.info("已有 {} 筆抽取結果，將略過這些日報", done.size());
        }

        int processed = 0;
        int failed = 0;
        for (Map<String, Object> report : reports) {
            String reportId = String.valueOf(report.get("report_id"));
            if (done.containsKey(reportId)) {
                continue;
            }
            List<StructuredTask> tasks = extractWithRetry(report, reportId);
            if (tasks == null) {
                failed++;
                continue;
            }
            done.put(reportId, tasks.stream().map(MockTaskExtractionRunner::toMap).collect(Collectors.toList()));
            save(done, reports);
            processed++;
            log.info("[{}/{}] 已抽取 {}：{} 個任務", done.size(), reports.size(), reportId, tasks.size());
            Thread.sleep(delayMillis);
        }
        log.info("抽取完成：本次新增 {} 筆，失敗 {} 筆，輸出檔案 {}", processed, failed, outputFile.getAbsolutePath());
        if (failed > 0) {
            log.warn("有 {} 筆失敗，請重新執行一次以補齊 (已完成的不會重複呼叫 Gemini)", failed);
        }
    }

    private List<StructuredTask> extractWithRetry(Map<String, Object> report, String reportId)
            throws InterruptedException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return extractionService.extract(report);
            } catch (RuntimeException exception) {
                log.warn("抽取 {} 失敗 (第 {}/{} 次)：{}", reportId, attempt, MAX_ATTEMPTS,
                        exception.getMessage());
                Thread.sleep(delayMillis * attempt * 2);
            }
        }
        return null;
    }

    /** 依 mock_daily_reports.json 的順序寫出，並在每筆完成後立即存檔，方便中斷續跑。 */
    private void save(Map<String, List<Map<String, Object>>> done, List<Map<String, Object>> reports)
            throws Exception {
        Set<String> order = reports.stream()
                .map(r -> String.valueOf(r.get("report_id")))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        List<Map<String, Object>> output = new ArrayList<>();
        for (String id : order) {
            if (done.containsKey(id)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("report_id", id);
                entry.put("tasks", done.get(id));
                output.add(entry);
            }
        }
        File parent = outputFile.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(outputFile, output);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> taskMaps(Map<String, Object> entry) {
        Object tasks = entry.get("tasks");
        return tasks instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static Map<String, Object> toMap(StructuredTask task) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("task_name", task.taskName());
        map.put("progress_percent", task.progressPercent());
        map.put("priority", task.priority().name());
        map.put("blocked", task.blocked());
        map.put("blocker_note", task.blockerNote());
        return map;
    }
}
