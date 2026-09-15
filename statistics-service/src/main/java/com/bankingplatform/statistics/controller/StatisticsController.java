package com.bankingplatform.statistics.controller;

import com.bankingplatform.statistics.dto.DailySnapshotResponse;
import com.bankingplatform.statistics.dto.PlatformStatsResponse;
import com.bankingplatform.statistics.dto.UserStatsResponse;
import com.bankingplatform.statistics.service.StatisticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/statistics")
@RequiredArgsConstructor
@Tag(name = "Statistics")
public class StatisticsController {

    private final StatisticsService statisticsService;

    @GetMapping("/platform")
    @Operation(summary = "Platform-wide aggregate stats")
    public ResponseEntity<PlatformStatsResponse> getPlatformStats() {
        return ResponseEntity.ok(statisticsService.getPlatformStats());
    }

    @GetMapping("/users/{userId}")
    @Operation(summary = "Per-user stats")
    public ResponseEntity<UserStatsResponse> getUserStats(@PathVariable Long userId) {
        return ResponseEntity.ok(statisticsService.getUserStats(userId));
    }

    @GetMapping("/daily")
    @Operation(summary = "Daily snapshot for a specific date (defaults to today)")
    public ResponseEntity<DailySnapshotResponse> getDailySnapshot(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(statisticsService.getDailySnapshot(date != null ? date : LocalDate.now()));
    }

    @GetMapping("/daily/range")
    @Operation(summary = "Daily snapshots for a date range")
    public ResponseEntity<List<DailySnapshotResponse>> getDailyRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok(statisticsService.getDailySnapshots(from, to));
    }
}
