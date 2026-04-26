package br.dev.claudiney.proxy.exchange;

import java.util.ArrayList;
import java.util.List;

public final class RecursionState {

    private final List<String> pendingServices;
    private int currentAttempt;

    public RecursionState(final List<String> serviceIds) {
        this.pendingServices = new ArrayList<>(serviceIds);
        this.currentAttempt = 0;
    }

    public boolean isEmpty() {
        return pendingServices.isEmpty();
    }

    public String currentServiceId() {
        return pendingServices.isEmpty() ? null : pendingServices.get(0);
    }

    public int currentAttempt() {
        return currentAttempt;
    }

    public void incrementAttempt() {
        currentAttempt++;
    }

    public void advanceToNext() {
        if (!pendingServices.isEmpty()) {
            pendingServices.remove(0);
        }
        currentAttempt = 0;
    }
}
