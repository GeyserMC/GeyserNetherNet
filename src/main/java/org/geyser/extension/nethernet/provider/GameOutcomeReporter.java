package org.geyser.extension.nethernet.provider;

import com.google.gson.JsonObject;
import dev.kastle.netty.channel.nethernet.admission.AdmissionPrincipal;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.packet.DisconnectPacket;
import org.cloudburstmc.protocol.bedrock.packet.RequestNetworkSettingsPacket;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;

/**
 * Game admission evidence, independent of native transport establishment and teardown.
 */
public final class GameOutcomeReporter {
    public static final String HANDLER_NAME = "nethernet-game-outcome";
    private static final AttributeKey<State> STATE = AttributeKey.valueOf(GameOutcomeReporter.class, "state");
    private final ArrayBlockingQueue<JsonObject> events = new ArrayBlockingQueue<>(256);
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean closed;

    // Owned by the channel: no global session map or references to native resources.
    private static final class State {
        boolean unsupportedVersion;
        boolean reported;
    }

    public ChannelDuplexHandler observer(IntPredicate supportedProtocol) {
        return new ChannelDuplexHandler() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object message) throws Exception {
                if (message instanceof BedrockPacketWrapper wrapper && primary(wrapper)
                        && wrapper.getPacket() instanceof RequestNetworkSettingsPacket settings) {
                    State state = state(ctx.channel());
                    if (state != null) synchronized (state) {
                        state.unsupportedVersion = !supportedProtocol.test(settings.getProtocolVersion());
                    }
                }
                ctx.fireChannelRead(message);
            }

            @Override
            public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) throws Exception {
                if (message instanceof BedrockPacketWrapper wrapper && primary(wrapper)
                        && wrapper.getPacket() instanceof DisconnectPacket) {
                    report(ctx.channel(), false);
                }
                ctx.write(message, promise);
            }
        };
    }

    private static boolean primary(BedrockPacketWrapper wrapper) {
        return wrapper.getSenderSubClientId() == 0 && wrapper.getTargetSubClientId() == 0;
    }

    private static State state(Channel channel) {
        if (channel.attr(AdmissionPrincipal.KEY).get() == null) return null;
        var attribute = channel.attr(STATE);
        State state = attribute.get();
        if (state != null) return state;
        State created = new State();
        State previous = attribute.setIfAbsent(created);
        return previous == null ? created : previous;
    }

    /**
     * Called only by Geyser's play-ready SessionJoinEvent, never by channel-open.
     */
    public void joined(Channel channel) {
        report(channel, true);
    }

    private void report(Channel channel, boolean joined) {
        if (closed) return;
        State state = state(channel);
        if (state == null) return;
        synchronized (state) {
            if (state.reported) return;
            state.reported = true; // A full telemetry queue must not turn a later disconnect into rejection.
            JsonObject event = new JsonObject();
            event.addProperty("ticketId", channel.attr(AdmissionPrincipal.KEY).get().ticketId());
            event.addProperty("stage", joined ? "ticket.game_joined" : "ticket.game_rejected");
            event.addProperty("occurredAt", Instant.now().toString());
            event.addProperty("reason", joined ? "play_ready" : state.unsupportedVersion ? "unsupported_version" : "server_rejected");
            // Never publish disconnect text, player identity, or game payloads.
            if (!events.offer(event)) dropped.incrementAndGet();
        }
    }

    public void drainTo(List<JsonObject> batch, int maximum) {
        events.drainTo(batch, maximum);
    }

    public long droppedEvents() {
        return dropped.get();
    }

    public void close() {
        closed = true;
        events.clear();
    }
}
