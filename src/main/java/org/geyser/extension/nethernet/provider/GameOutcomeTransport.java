package org.geyser.extension.nethernet.provider;

import com.google.gson.JsonObject;
import org.cloudburstmc.netty.signalling.ProviderTransport;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;

/**
 * Uses the provider client's existing signed, durable ticket-event delivery.
 */
public final class GameOutcomeTransport implements ProviderTransport {
    private final ProviderTransport delegate;
    private final GameOutcomeReporter outcomes;

    public GameOutcomeTransport(ProviderTransport delegate, GameOutcomeReporter outcomes) {
        this.delegate = delegate;
        this.outcomes = outcomes;
    }

    @Override
    public CompletionStage<JsonObject> hostProfile() {
        return delegate.hostProfile();
    }

    @Override
    public CompletionStage<Void> installTicketKeys(List<TicketKey> keys) {
        return delegate.installTicketKeys(keys);
    }

    @Override
    public CompletionStage<ApplyResult> applyState(String state) {
        return delegate.applyState(state);
    }

    @Override
    public boolean supportsGameOutcomes() {
        return true;
    }

    @Override
    public List<JsonObject> pollEvents() {
        List<JsonObject> batch = new ArrayList<>(delegate.pollEvents());
        outcomes.drainTo(batch, Math.max(0, 100 - batch.size()));
        return batch;
    }

    @Override
    public CompletionStage<Void> drain() {
        return delegate.drain();
    }

    @Override
    public CompletionStage<Void> close() {
        outcomes.close();
        return delegate.close();
    }
}
