package com.rpatest.execution.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.rpatest.config.ReportProperties;
import com.rpatest.execution.domain.RunStatus;
import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.EnqueueExchangeQueueDto;
import com.rpatest.orchestrator.exception.OrchestratorApiException;
import com.rpatest.scenario.domain.ScenarioStepType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReportNotifierTest {

    private ExchangeQueuesPort port;
    private ReportProperties properties;
    private ReportNotifier notifier;

    @BeforeEach
    void setUp() {
        port = mock(ExchangeQueuesPort.class);
        properties = new ReportProperties();
        properties.getNotification().setEnabled(true);
        notifier = new ReportNotifier(port, properties);
    }

    @Test
    void doesNothingWhenDisabled() {
        properties.getNotification().setEnabled(false);

        notifier.notifyFinished(ReportFixtures.succeededChain());

        verify(port, never()).enqueue(any(), any());
    }

    @Test
    void queuesAMailTransactionToTheMailerQueue() {
        notifier.notifyFinished(ReportFixtures.succeededChain());

        ArgumentCaptor<EnqueueExchangeQueueDto> item = ArgumentCaptor.forClass(EnqueueExchangeQueueDto.class);
        verify(port).enqueue(eq("SND"), item.capture());
        assertThat(item.getValue().value()).isEqualTo("Sandbox");
        assertThat(item.getValue().naturalKey()).isEqualTo("ID07102026204623_SandboxReport");
        Map<String, String> metadata = item.getValue().metadata();
        assertThat(metadata).containsEntry("Mail_To", "ivanov");
        assertThat(metadata.get("Mail_Subject")).isEqualTo("Отчет по запуску сценария \"Сверка платежей\" №12 за 07.10.2026");
        assertThat(metadata.get("Mail_Body")).contains("Тестирование завершено").contains("УСПЕШНО").contains("Сверка платежей")
                .contains("6 мин 23 с").doesNotContain("<a href");
    }

    @Test
    void mailBodyHasTheErrorAndTheLinkWhenConfigured() {
        properties.getNotification().setPublicBaseUrl("https://host:8443/");
        RunReportSnapshot failed = ReportFixtures.report(RunStatus.FAILED, "S<b>", List.of(
                ReportFixtures.step(1, "Задание", ScenarioStepType.JOB, RunStatus.FAILED, 3L, "Assignment failed on robot 'r1': <no>",
                        Map.of("robotName", "r1", "success", false, "robotError", "<no>"))), List.of());

        notifier.notifyFinished(failed);

        ArgumentCaptor<EnqueueExchangeQueueDto> item = ArgumentCaptor.forClass(EnqueueExchangeQueueDto.class);
        verify(port).enqueue(eq("SND"), item.capture());
        String body = item.getValue().metadata().get("Mail_Body");
        assertThat(body).contains("ЕСТЬ ОШИБКИ").contains("Ошибка в шаге «Задание»").contains("Задание завершилось с ошибкой на роботе «r1»").contains("&lt;no&gt;")
                .contains("S&lt;b&gt;").contains("href=\"https://host:8443/api/v1/runs/12/report\"")
                .doesNotContain("<no>").doesNotContain("S<b>");
    }

    @Test
    void appendsTheConfiguredMailDomainToTheLogin() {
        properties.getNotification().setMailDomain("example.com");

        notifier.notifyFinished(ReportFixtures.succeededChain());

        ArgumentCaptor<EnqueueExchangeQueueDto> item = ArgumentCaptor.forClass(EnqueueExchangeQueueDto.class);
        verify(port).enqueue(eq("SND"), item.capture());
        assertThat(item.getValue().metadata()).containsEntry("Mail_To", "ivanov@example.com");
    }

    @Test
    void mailRecipientHandlesDomainVariantsAndLoginsThatAreAlreadyAddresses() {
        assertThat(ReportMail.recipient("ivanov", "example.com")).isEqualTo("ivanov@example.com");
        assertThat(ReportMail.recipient("ivanov", "@example.com")).isEqualTo("ivanov@example.com");
        assertThat(ReportMail.recipient("ivanov", " example.com ")).isEqualTo("ivanov@example.com");
        assertThat(ReportMail.recipient("ivanov@other.org", "example.com")).isEqualTo("ivanov@other.org");
        assertThat(ReportMail.recipient("ivanov", "")).isEqualTo("ivanov");
        assertThat(ReportMail.recipient("ivanov", null)).isEqualTo("ivanov");
    }

    @Test
    void usesConfiguredQueueAndValue() {
        properties.getNotification().setQueueName("MAIL");
        properties.getNotification().setValue("Other");

        notifier.notifyFinished(ReportFixtures.succeededChain());

        ArgumentCaptor<EnqueueExchangeQueueDto> item = ArgumentCaptor.forClass(EnqueueExchangeQueueDto.class);
        verify(port).enqueue(eq("MAIL"), item.capture());
        assertThat(item.getValue().value()).isEqualTo("Other");
    }

    @Test
    void skipsWhenThereIsNobodyToMail() {
        RunReportSnapshot base = ReportFixtures.succeededChain();
        RunReportSnapshot noUser = new RunReportSnapshot(base.runId(), base.scenarioId(), base.scenarioName(), " ", base.status(),
                base.startedAt(), base.finishedAt(), base.durationSeconds(), null, base.steps(), base.edges(), base.generatedAt());

        notifier.notifyFinished(noUser);

        verify(port, never()).enqueue(any(), any());
    }

    @Test
    void aFailingQueueNeverPropagates() {
        doThrow(new OrchestratorApiException("queue SND does not exist")).when(port).enqueue(any(), any());

        notifier.notifyFinished(ReportFixtures.succeededChain());

        verify(port).enqueue(any(), any());
    }
}
