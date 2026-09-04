package com.rpatest.execution.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rpatest.orchestrator.client.ExchangeQueuesPort;
import com.rpatest.orchestrator.dto.ExchangeQueueDto;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExchangeQueueProvisionerTest {

    private ExchangeQueuesPort exchangeQueuesPort;
    private ExchangeQueueProvisioner provisioner;

    @BeforeEach
    void setUp() {
        exchangeQueuesPort = mock(ExchangeQueuesPort.class);
        provisioner = new ExchangeQueueProvisioner(exchangeQueuesPort);
    }

    @Test
    void returnsExistingQueueWithoutCreating() {
        UUID id = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.of(new ExchangeQueueDto(id, "q", null, 0, 0)));

        ExchangeQueueDto result = provisioner.ensureExists("q", "desc", null, null);

        assertThat(result.id()).isEqualTo(id);
        verify(exchangeQueuesPort, never()).create(any());
    }

    @Test
    void createsQueueWhenMissing() {
        UUID id = UUID.randomUUID();
        when(exchangeQueuesPort.findByName("q"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(new ExchangeQueueDto(id, "q", null, 0, 0)));

        ExchangeQueueDto result = provisioner.ensureExists("q", "desc", 60, 3);

        assertThat(result.id()).isEqualTo(id);
        verify(exchangeQueuesPort).create(any());
    }

    @Test
    void throwsWhenStillMissingAfterCreate() {
        when(exchangeQueuesPort.findByName("q")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> provisioner.ensureExists("q", null, null, null))
                .isInstanceOf(StepExecutionException.class);
    }
}
