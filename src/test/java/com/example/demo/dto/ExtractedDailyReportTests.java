package com.example.demo.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtractedDailyReportTests {

    @Test
    void serializesDateAndAccessAsStableJsonValues() throws Exception {
        ExtractedDailyReport report = new ExtractedDailyReport(
                "REP-20261120-01",
                "Alex",
                LocalDate.of(2026, 11, 20),
                RoleAccess.DEVELOPER,
                null,
                "Completed the report extraction agent");

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(report);

        assertThat(json)
                .contains("\"date\":\"2026-11-20\"")
                .contains("\"roleAccess\":\"DEVELOPER\"")
                .contains("\"ipAddress\":\"NONE\"");
    }

    @Test
    void rejectsBlankRequiredFields() {
        assertThatThrownBy(() -> new ExtractedDailyReport(
                "REP-20261120-01",
                "Alex",
                LocalDate.of(2026, 11, 20),
                RoleAccess.DEVELOPER,
                " ",
                " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("summary must not be blank");
    }

    @Test
    void allowsTheServiceToGenerateAMissingReportId() {
        ExtractedDailyReport report = new ExtractedDailyReport(
                null,
                "Alex",
                LocalDate.of(2026, 11, 20),
                RoleAccess.DEVELOPER,
                null,
                "Completed the report extraction agent");

        assertThat(report.reportId()).isNull();
    }
}
