package com.mmda.serverless.provider.simulators;

/** Agenda um callback simulado para daqui a {@code delaySeconds} (§10). */
public interface ScheduledCallbacks {

    void schedule(CallbackOrder order, int delaySeconds);
}
