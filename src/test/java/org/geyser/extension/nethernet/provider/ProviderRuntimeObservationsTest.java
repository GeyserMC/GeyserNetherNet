package org.geyser.extension.nethernet.provider;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderRuntimeObservationsTest {
    @Test void acceptsFleetRegistrationsWithoutAnOriginatingPublicAddress() {
        var registration = JsonParser.parseString("{\"instanceId\":\"game-one\"}").getAsJsonObject();
        assertTrue(ProviderRuntimeObservations.registrationMessage(registration).contains("game-one"));
        registration.add("publicAddress", com.google.gson.JsonNull.INSTANCE);
        assertTrue(ProviderRuntimeObservations.registrationMessage(registration).contains("attached Signal Servers"));
        registration.addProperty("publicAddress", "https://play.example");
        assertEquals("Provider address: https://play.example (instance game-one)", ProviderRuntimeObservations.registrationMessage(registration));
    }

    @Test void reportsActualPlayersEvenWhenAdmissionCapacityHasBeenReduced() {
        var health = ProviderRuntimeObservations.health(143, 100, 123456789L, "test");
        assertEquals(143, health.playerCount().connectedPlayers());
        assertEquals(123456789L, health.playerCount().sampledAt());
        assertEquals(100, health.capacity());
        assertEquals(1, health.load());
        assertEquals(0, ProviderRuntimeObservations.health(0, 100, 123456790L, "test").playerCount().connectedPlayers());
    }
}
