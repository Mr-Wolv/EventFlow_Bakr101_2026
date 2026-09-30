package com.eventflow.fulfillment;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailureInjectorTest {

    @Test
    void alwaysFaultFailsEveryAttemptAndCountsEachFailure() {
        FailureInjector injector = new FailureInjector();
        injector.setFault(FailureInjector.FaultType.ALWAYS);
        UUID eventId = UUID.randomUUID();

        assertThatThrownBy(() -> injector.maybeFail(eventId)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> injector.maybeFail(eventId)).isInstanceOf(IllegalStateException.class);

        assertThat(injector.injectedFailures()).isEqualTo(2);
    }

    @Test
    void oncePerEventFaultFailsOnlyTheFirstAttemptForEachEvent() {
        FailureInjector injector = new FailureInjector();
        injector.setFault(FailureInjector.FaultType.ONCE_PER_EVENT);
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();

        assertThatThrownBy(() -> injector.maybeFail(firstEventId)).isInstanceOf(IllegalStateException.class);
        injector.maybeFail(firstEventId);
        assertThatThrownBy(() -> injector.maybeFail(secondEventId)).isInstanceOf(IllegalStateException.class);

        assertThat(injector.injectedFailures()).isEqualTo(2);
    }

    @Test
    void noneFaultAllowsProcessing() {
        FailureInjector injector = new FailureInjector();

        injector.maybeFail(UUID.randomUUID());

        assertThat(injector.currentFault()).isEqualTo(FailureInjector.FaultType.NONE);
        assertThat(injector.injectedFailures()).isZero();
    }
}