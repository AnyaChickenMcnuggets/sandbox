package com.rpatest.execution.report;

import com.rpatest.config.ReportProperties;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.EnqueueExchangeQueueDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Tells the person who started the run that it finished, by adding a transaction to the
 * orchestrator's mailer queue ({@code report.notification.*}). Off by default. A failure here must
 * never affect the run or its report - it is logged and swallowed.
 */
@Component
public class ReportNotifier {

    private static final Logger log = LoggerFactory.getLogger(ReportNotifier.class);

    private final ExchangeQueuesPort exchangeQueuesPort;
    private final ReportProperties properties;

    public ReportNotifier(ExchangeQueuesPort exchangeQueuesPort, ReportProperties properties) {
        this.exchangeQueuesPort = exchangeQueuesPort;
        this.properties = properties;
    }

    public void notifyFinished(RunReportSnapshot report) {
        ReportProperties.Notification config = properties.getNotification();
        if (!config.isEnabled()) {
            return;
        }
        if (report.triggeredBy() == null || report.triggeredBy().isBlank()) {
            log.warn("Run {} has no triggeredBy, report notification skipped", report.runId());
            return;
        }
        try {
            String naturalKey = ReportMail.naturalKey(report);
            exchangeQueuesPort.enqueue(config.getQueueName(), EnqueueExchangeQueueDto.of(
                    naturalKey, config.getValue(), ReportMail.metadata(report, config.getPublicBaseUrl(), config.getMailDomain())));
            log.info("Report notification for run {} queued to '{}' (naturalKey={}, to={})",
                    report.runId(), config.getQueueName(), naturalKey,
                    ReportMail.recipient(report.triggeredBy().trim(), config.getMailDomain()));
        } catch (RuntimeException e) {
            log.warn("Failed to queue report notification for run {}", report.runId(), e);
        }
    }
}
