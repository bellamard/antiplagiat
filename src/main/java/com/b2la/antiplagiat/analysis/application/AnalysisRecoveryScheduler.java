package com.b2la.antiplagiat.analysis.application;

import com.b2la.antiplagiat.entites.AnalysisHistory;
import com.b2la.antiplagiat.enumerote.StatusEnum;
import com.b2la.antiplagiat.repository.AnalysisHistoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class AnalysisRecoveryScheduler {

    private final AnalysisHistoryRepository historyRepository;
    private final AnalysisWorkerService analysisWorkerService;
    private final long processingTimeoutMinutes;

    public AnalysisRecoveryScheduler(
            AnalysisHistoryRepository historyRepository,
            AnalysisWorkerService analysisWorkerService,
            @Value("${analysis.worker.processing-timeout-minutes:30}") long processingTimeoutMinutes
    ) {
        this.historyRepository = historyRepository;
        this.analysisWorkerService = analysisWorkerService;
        this.processingTimeoutMinutes = Math.max(1, processingTimeoutMinutes);
    }

    @Scheduled(fixedDelayString = "${analysis.worker.recovery-interval-millis:300000}")
    public void recoverStuckAnalyses() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(processingTimeoutMinutes);
        for (AnalysisHistory history : historyRepository.findByStatusLibelleAndStartedAtBefore(StatusEnum.PROCESSING, cutoff)) {
            if (history.getAttempts() >= history.getMaxAttempts()) {
                continue;
            }
            analysisWorkerService.process(history.getId());
        }
    }
}
