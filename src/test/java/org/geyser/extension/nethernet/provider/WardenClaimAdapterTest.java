package org.geyser.extension.nethernet.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WardenClaimAdapterTest {
    private static JsonObject metadata() {
        return JsonParser.parseString("""
            {"cloud.warden.claim":{"version":1,"critical":false,"data":{"available":true,
              "action":{"url":"https://panel.warden.cloud/claim/private","expiresAt":2000,"text":"Optionally claim your service"}}}}
            """).getAsJsonObject();
    }

    @Test void optionalActionIsDisplayedButNeverAutomaticallyRefreshed() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var adapter = new WardenClaimAdapter(() -> CompletableFuture.completedFuture(metadata()),
            () -> CompletableFuture.completedFuture(metadata()), () -> {
            requests.incrementAndGet(); JsonObject result = new JsonObject(); result.add("extensions", metadata());
            return CompletableFuture.completedFuture(result);
        }, () -> 1000);
        assertTrue(adapter.current().toCompletableFuture().get().orElseThrow().message().contains("/claim/private"));
        assertEquals(0, requests.get());
        assertTrue(adapter.refresh().toCompletableFuture().get().isPresent());
        assertEquals(1, requests.get());
    }

    @Test void independentProviderHasNoClaimRequestsEvenForAnExplicitCommand() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var adapter = new WardenClaimAdapter(() -> CompletableFuture.completedFuture(new JsonObject()),
            () -> CompletableFuture.completedFuture(new JsonObject()), () -> {
            requests.incrementAndGet(); return CompletableFuture.completedFuture(new JsonObject());
        }, () -> 1000);
        assertTrue(adapter.current().toCompletableFuture().get().isEmpty());
        assertTrue(adapter.refresh().toCompletableFuture().get().isEmpty());
        assertEquals(0, requests.get());
    }

    @Test void restartCanExplicitlyRefreshThroughReadinessWithoutAStoredClaimUrl() throws Exception {
        JsonObject readiness = metadata();
        readiness.getAsJsonObject(WardenClaimAdapter.NAMESPACE).getAsJsonObject("data").remove("action");
        AtomicInteger requests = new AtomicInteger();
        var adapter = new WardenClaimAdapter(() -> CompletableFuture.completedFuture(new JsonObject()),
            () -> CompletableFuture.completedFuture(readiness), () -> {
                requests.incrementAndGet(); JsonObject response = new JsonObject(); response.add("extensions", metadata());
                return CompletableFuture.completedFuture(response);
            }, () -> 1000);
        assertTrue(adapter.current().toCompletableFuture().get().isEmpty());
        assertEquals(0, requests.get());
        assertTrue(adapter.refresh().toCompletableFuture().get().isPresent());
        assertEquals(1, requests.get());
    }

    @Test void expiredUnavailableMalformedAndUnsupportedActionsAreNotDisplayed() {
        assertTrue(WardenClaimAdapter.action(metadata(), 2000).isEmpty());
        JsonObject unavailable = metadata(); unavailable.getAsJsonObject(WardenClaimAdapter.NAMESPACE).getAsJsonObject("data").addProperty("available", false);
        assertTrue(WardenClaimAdapter.action(unavailable, 1000).isEmpty());
        JsonObject unsupported = metadata(); unsupported.getAsJsonObject(WardenClaimAdapter.NAMESPACE).addProperty("version", 2);
        assertTrue(WardenClaimAdapter.action(unsupported, 1000).isEmpty());
        JsonObject unsafe = metadata(); unsafe.getAsJsonObject(WardenClaimAdapter.NAMESPACE).getAsJsonObject("data").getAsJsonObject("action").addProperty("url", "http://insecure.invalid/claim");
        assertTrue(WardenClaimAdapter.action(unsafe, 1000).isEmpty());
        JsonObject malformed = metadata(); malformed.getAsJsonObject(WardenClaimAdapter.NAMESPACE).getAsJsonObject("data").getAsJsonObject("action").addProperty("text", "claim\u001b[31m");
        assertTrue(WardenClaimAdapter.action(malformed, 1000).isEmpty());
    }
}
