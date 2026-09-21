package com.synapse.backend;

import com.synapse.backend.models.CsvDocument;
import com.synapse.backend.models.CsvHealthCheckReport;
import com.synapse.backend.services.CsvHealthAndHealService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class CsvHealthAndHealServiceTest {

    private CsvHealthAndHealService healService;

    @BeforeEach
    void setUp() {
        healService = new CsvHealthAndHealService();
    }

    @Test
    void testCleanCsvHealthCheck() {
        String cleanCsv = "id,name,age,salary\n1,Alice,30,85000\n2,Bob,25,65000\n";
        CsvDocument doc = healService.inspectAndHeal("clean.csv", cleanCsv.getBytes(StandardCharsets.UTF_8));

        assertNotNull(doc);
        CsvHealthCheckReport report = doc.getHealthReport();
        assertEquals("HEALTHY", report.getStatus());
        assertFalse(report.isAutoHealed());
        assertEquals(4, doc.getHeaders().size());
        assertEquals(3, report.getHealedRowCount()); // header + 2 rows
    }

    @Test
    void testCorruptedCsvAutoHealing() {
        // Corrupted CSV:
        // 1. blank header and duplicate header
        // 2. empty blank lines
        // 3. ragged underflow row (row 2 missing age and salary)
        // 4. ragged overflow row (row 3 has extra unquoted data)
        String corruptedCsv = "id,,name,id\n\n1,Alpha\n2,Beta,40,90000,Bonus,Extra\n3,Gamma,35,75000\n\n";
        CsvDocument doc = healService.inspectAndHeal("corrupted.csv", corruptedCsv.getBytes(StandardCharsets.UTF_8));

        assertNotNull(doc);
        CsvHealthCheckReport report = doc.getHealthReport();
        assertEquals("CORRUPTED_HEALED", report.getStatus());
        assertTrue(report.isAutoHealed());
        assertTrue(report.getIssues().size() >= 3);

        // Header deduplication and blank column healed
        assertEquals("id", doc.getHeaders().get(0));
        assertEquals("column_2", doc.getHeaders().get(1));
        assertEquals("name", doc.getHeaders().get(2));
        assertEquals("id_4", doc.getHeaders().get(3));

        // Healed text should parse cleanly with rectangular columns
        assertNotNull(doc.getHealedCsvText());
        assertTrue(doc.getHealedCsvText().contains("column_2"));
        assertTrue(doc.getHealedCsvText().contains("id_4"));
    }

    @Test
    void testSemicolonDelimiterNormalization() {
        String semicolonCsv = "dept;headcount;budget\nEngineering;45;1200000\nProduct;15;450000\n";
        CsvDocument doc = healService.inspectAndHeal("dept.csv", semicolonCsv.getBytes(StandardCharsets.UTF_8));

        assertNotNull(doc);
        CsvHealthCheckReport report = doc.getHealthReport();
        assertEquals(";", report.getDetectedDelimiter());
        assertEquals(3, doc.getHeaders().size());
        assertEquals("dept", doc.getHeaders().get(0));
        assertEquals("headcount", doc.getHeaders().get(1));
        assertEquals("budget", doc.getHeaders().get(2));
    }
}
