package com.ticketrush.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Behaviour knobs (spec §10). Mutable on purpose: tests flip them at runtime; in Compose they come from env.
 */
@ConfigurationProperties("provider")
public class ProviderSettings {

    private volatile double declineRate;
    private volatile long latencyMs;
    private volatile double timeoutAfterChargeRate;
    private volatile long timeoutSleepMs = 5000;
    private volatile boolean down;

    public double getDeclineRate() { return declineRate; }
    public void setDeclineRate(double declineRate) { this.declineRate = declineRate; }
    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }
    public double getTimeoutAfterChargeRate() { return timeoutAfterChargeRate; }
    public void setTimeoutAfterChargeRate(double rate) { this.timeoutAfterChargeRate = rate; }
    public long getTimeoutSleepMs() { return timeoutSleepMs; }
    public void setTimeoutSleepMs(long timeoutSleepMs) { this.timeoutSleepMs = timeoutSleepMs; }
    public boolean isDown() { return down; }
    public void setDown(boolean down) { this.down = down; }
}
