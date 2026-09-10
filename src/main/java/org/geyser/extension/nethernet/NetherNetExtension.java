package org.geyser.extension.nethernet;

import com.google.gson.Gson;
import dev.kastle.netty.channel.nethernet.NetherNetChannelFactory;
import dev.kastle.netty.channel.nethernet.admission.NativeAdmissionServerChannel;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetHTTPSignaling;
import dev.kastle.netty.channel.nethernet.signaling.NetherNetServerSignaling;
import dev.kastle.netty.util.nethernet.NetherNetLogging;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import org.cloudburstmc.netty.signalling.ProviderClient;
import org.cloudburstmc.netty.signalling.ProviderStateStore;
import org.cloudburstmc.netty.signalling.ProviderTransport;
import org.cloudburstmc.netty.signalling.ServerStatus;
import org.cloudburstmc.protocol.bedrock.BedrockPong;
import org.geyser.extension.nethernet.nethernet.DummyPingChannel;
import org.geyser.extension.nethernet.nethernet.NetherNetChannelInitialiser;
import org.geyser.extension.nethernet.provider.GameOutcomeReporter;
import org.geyser.extension.nethernet.provider.GameOutcomeTransport;
import org.geyser.extension.nethernet.provider.GeyserStatusCollector;
import org.geyser.extension.nethernet.provider.ProviderHostFactory;
import org.geyser.extension.nethernet.provider.ProviderRuntimeConfiguration;
import org.geyser.extension.nethernet.provider.ProviderRuntimeObservations;
import org.geyser.extension.nethernet.provider.ProviderShutdown;
import org.geyser.extension.nethernet.provider.WardenClaimAdapter;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.GeyserImpl;
import org.geysermc.geyser.api.command.Command;
import org.geysermc.geyser.api.command.CommandSource;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.bedrock.SessionJoinEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostReloadEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.api.network.BedrockListener;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class NetherNetExtension implements Extension {
    private static final Channel PING_CHANNEL = new DummyPingChannel();

    private volatile Config config;
    private final Object providerLifecycle = new Object();
    private volatile boolean stopping;
    private volatile ProviderClient providerClient;
    private volatile ProviderShutdown providerShutdown;
    private volatile WardenClaimAdapter wardenClaim;
    private final GameOutcomeReporter gameOutcomes = new GameOutcomeReporter();
    private volatile Supplier<ServerStatus> providerStatusSupplier = this::collectServerStatus;

    private EventLoopGroup eventLoopGroup;
    private Channel netherNetChannel;
    private NetherNetServerSignaling signaling;
    private EventLoopGroup inbuiltEventLoopGroup;
    private Channel inbuiltChannel;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        this.logger().info("Loading %s...".formatted(this.description().name()));

        File configFile = dataFolder().resolve("config.yml").toFile();

        // Ensure the data folder exists
        if (!dataFolder().toFile().exists()) {
            if (!dataFolder().toFile().mkdirs()) {
                this.logger().error("Failed to create data folder, extension will not start!");
                this.disable();
                return;
            }
        }

        // Load our configuration
        try {
            config = ConfigLoader.loadConfig(configFile);
        } catch (IOException e) {
            this.logger().error("Failed to load config, extension will not start!", e);
            this.disable();
            return;
        }

        String mode = config.signalling();
        if (mode.equals("none")) return;
        if (mode.equals("hybrid") || mode.equals("inbuilt")) startInbuilt();
        if (mode.equals("hybrid") || mode.equals("nxs")) startProvider();
    }

    private void startInbuilt() {
        if (geyserApi().bedrockListener().port() == GeyserImpl.getInstance().config().java().port()) {
            logger().warning("Skipping inbuilt signalling: its TCP port matches the Java server port.");
            return;
        }
        // Keep libdatachannel's own logging out of the way
        NetherNetLogging.setNativeLogLevel("WARN");

        // Start up NetherNet
        try {
            // Create a private signing identity automatically on the first start.
            org.geyser.extension.nethernet.admission.InbuiltIdentity.ensure(dataFolder());
            NetherNetHTTPSignaling.Builder signallingBuilder = new NetherNetHTTPSignaling.Builder()
                    .setIdentityKeystore(this.dataFolder().resolve("identity.p12").toFile(), "")
                    .setMotdProvider((host, remoteAddress) -> {
                        BedrockPong pong = GeyserImpl.getInstance().getGeyserServer().onQuery(PING_CHANNEL, remoteAddress);

                        return new NetherNetServerSignaling.PongData.Builder()
                                .setServerName(pong.motd())
                                .setProtocol(pong.protocolVersion())
                                .setVersion(pong.version())
                                .setPlayerCount(pong.playerCount())
                                .setMaxPlayerCount(pong.maximumPlayerCount())
                                .build();
                    });

            this.signaling = signallingBuilder.build();

            this.inbuiltEventLoopGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());

            ServerBootstrap b = new ServerBootstrap();
            b.group(inbuiltEventLoopGroup)
                    .channelFactory(NetherNetChannelFactory.server(signaling))
                    .childHandler(new NetherNetChannelInitialiser(GeyserImpl.getInstance()));

            BedrockListener listener = this.geyserApi().bedrockListener();
            this.inbuiltChannel = b.bind(new InetSocketAddress(listener.address(), listener.port())).sync().channel();

            this.logger().info("Inbuilt signalling started on http://" + listener.address() + ":" + listener.port());
        } catch (Exception e) {
            closeInbuiltResources();
            this.logger().warning("Inbuilt signalling could not bind or initialize; check for an occupied TCP port. NXS can still start.");
        }
    }

    private void startProvider() {
        CompletableFuture.runAsync(() -> {
            ProviderStateStore store = null;
            ProviderTransport initializingTransport = null;
            try {
                var listener = geyserApi().bedrockListener();
                ProviderRuntimeConfiguration runtime = ProviderRuntimeConfiguration.resolve(config, dataFolder(), listener.address(), listener.port(), collectServerStatus().maxPlayers());
                URI origin = runtime.origin();
                var statePath = runtime.stateDirectory();
                ProviderTransport transport;
                if (stopping) return;
                // Lock the durable instance before identity initialization or opening its endpoint.
                store = new ProviderStateStore(statePath);
                ProviderHostFactory factory = ServiceLoader.load(ProviderHostFactory.class, getClass().getClassLoader()).findFirst().orElseThrow(() -> new IOException("Native provider host factory is unavailable; readiness cannot start"));
                eventLoopGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
                ServerBootstrap bootstrap = new ServerBootstrap().group(eventLoopGroup).childHandler(new NetherNetChannelInitialiser(GeyserImpl.getInstance(), gameOutcomes));
                ProviderHostFactory.Host host = factory.open(bootstrap, new InetSocketAddress(runtime.bindAddress(), runtime.udpPort()), Map.of("stateDirectory", statePath.toAbsolutePath().toString(), "profile", runtime.profile(),
                        "advertisedEndpoints", runtime.encodedAdvertisedEndpoints(),
                        "localDevelopment", Boolean.toString(java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(origin.getHost())))).toCompletableFuture().get(30, java.util.concurrent.TimeUnit.SECONDS);
                netherNetChannel = host.channel();
                transport = host.transport();
                host.warnings().forEach(message -> logger().warning(message));
                if (stopping) {
                    transport.close();
                    netherNetChannel.close();
                    eventLoopGroup.shutdownGracefully();
                    return;
                }
                initializingTransport = transport;
                transport = new GameOutcomeTransport(transport, gameOutcomes);
                ProviderClient client = new ProviderClient(runtime.clientConfiguration(), store, transport,
                        () -> providerStatusSupplier.get(), () -> ProviderRuntimeObservations.health(GeyserImpl.getInstance().getSessionManager().size(), runtime.capacity(), System.currentTimeMillis(), description().version()), message -> logger().warning(message));
                store = null; // ProviderClient now owns its lifetime.
                initializingTransport = null;
                synchronized (providerLifecycle) {
                    if (stopping) {
                        client.close();
                        return;
                    }
                    try {
                        providerShutdown = new ProviderShutdown(client::stop, this::closeNetworkResources, message -> logger().warning(message));
                    } catch (RuntimeException failure) {
                        client.close();
                        throw failure;
                    }
                    providerClient = client;
                    wardenClaim = new WardenClaimAdapter(client);
                }
                client.start().whenComplete((registration, failure) -> {
                    if (failure != null) {
                        logger().error("Provider startup failed: " + providerFailure(failure));
                        stopProvider();
                        return;
                    }
                    logger().info(ProviderRuntimeObservations.registrationMessage(registration));
                    WardenClaimAdapter claim = wardenClaim;
                    if (claim != null)
                        claim.current().thenAccept(action -> action.ifPresent(value -> logger().info(value.message())));
                });
            } catch (Exception e) {
                if (initializingTransport != null) initializingTransport.close();
                logger().error("Provider startup failed: " + (e instanceof IOException ? e.getMessage() : providerFailure(e)));
                closeNetworkResources();
            } finally {
                if (store != null) try {
                    store.close();
                } catch (IOException ignored) {
                }
            }
        });
    }

    private ServerStatus collectServerStatus() {
        GeyserImpl geyser = GeyserImpl.getInstance();
        BedrockPong pong = geyser.getGeyserServer().onQuery(PING_CHANNEL, new InetSocketAddress("127.0.0.1", 0));
        return GeyserStatusCollector.snapshot(pong, geyser.getSessionManager().size(), "", 0);
    }

    /**
     * Programmatic complete status override; panel fixed values still take precedence at the provider.
     */
    public void setServerStatus(ServerStatus snapshot) {
        providerStatusSupplier = () -> snapshot;
        refreshProviderStatus();
    }

    public void setServerStatusSupplier(Supplier<ServerStatus> supplier) {
        providerStatusSupplier = java.util.Objects.requireNonNull(supplier);
        refreshProviderStatus();
    }

    public void restoreAutomaticServerStatus() {
        providerStatusSupplier = this::collectServerStatus;
        refreshProviderStatus();
    }

    /**
     * Local console operations use the same typed public status API as other extensions.
     */
    @Subscribe
    public void onDefineCommands(GeyserDefineCommandsEvent event) {
        event.register(Command.<CommandSource>builder(this).source(CommandSource.class).name("status")
                .description("Inspect or update the provider's automatic status report")
                .permission("nethernet.console").suggestedOpOnly(true).executableOnConsole(true)
                .executor((source, command, args) -> {
                    if (!source.isConsole()) {
                        source.sendMessage("This operation requires the server console.");
                        return;
                    }
                    try {
                        if (args.length == 0) source.sendMessage(new Gson().toJson(providerStatusSupplier.get()));
                        else if (args.length == 1 && args[0].equals("automatic")) {
                            restoreAutomaticServerStatus();
                            source.sendMessage("Automatic Geyser status restored.");
                        } else if (args.length == 2 && args[0].equals("set") && args[1].length() <= 4096) {
                            String json = new String(java.util.Base64.getUrlDecoder().decode(args[1]), java.nio.charset.StandardCharsets.UTF_8);
                            setServerStatus(new Gson().fromJson(json, ServerStatus.class));
                            source.sendMessage("Complete provider status queued.");
                        } else
                            source.sendMessage("Usage: nethernet status [automatic | set <base64url JSON snapshot>]");
                    } catch (RuntimeException invalid) {
                        source.sendMessage("Invalid complete server status snapshot.");
                    }
                }).build());
        event.register(Command.<CommandSource>builder(this).source(CommandSource.class).name("claim")
                .description("Refresh an optional Warden ownership link")
                .permission("nethernet.console").suggestedOpOnly(true).executableOnConsole(true)
                .executor((source, command, args) -> {
                    if (!source.isConsole()) {
                        source.sendMessage("This operation requires the server console.");
                        return;
                    }
                    WardenClaimAdapter claim = wardenClaim;
                    if (claim == null) {
                        source.sendMessage("Provider is not running.");
                        return;
                    }
                    claim.refresh().whenComplete((action, failure) -> source.sendMessage(failure == null
                            ? action.map(WardenClaimAdapter.Action::message).orElse("This provider has no available Warden ownership action.")
                            : "Ownership action unavailable: " + providerFailure(failure)));
                }).build());
        event.register(Command.<CommandSource>builder(this).source(CommandSource.class).name("diagnostics")
                .description("Inspect native allocation counters and signed provider readiness")
                .permission("nethernet.console").suggestedOpOnly(true).executableOnConsole(true)
                .executor((source, command, args) -> {
                    if (!source.isConsole()) {
                        source.sendMessage("This operation requires the server console.");
                        return;
                    }
                    ProviderClient client = providerClient;
                    if (client == null) {
                        source.sendMessage("Provider is not running.");
                        return;
                    }
                    if (netherNetChannel instanceof NativeAdmissionServerChannel nativeChannel && nativeChannel.isActive()) {
                        source.sendMessage(new Gson().toJson(Map.of("nativeCreationAttempts", nativeChannel.creationAttempts(),
                                "admission", nativeChannel.admissionStats(), "native", nativeChannel.nativeStats(), "bind", nativeChannel.localAddress().toString())));
                    }
                    source.sendMessage(new Gson().toJson(Map.of("droppedGameOutcomeEvents", gameOutcomes.droppedEvents())));
                    client.readiness().whenComplete((ready, failure) -> source.sendMessage(failure == null ? ready.toString() : providerFailure(failure)));
                }).build());
    }

    private static String providerFailure(Throwable failure) {
        while (failure.getCause() != null && (failure instanceof java.util.concurrent.CompletionException || failure instanceof java.util.concurrent.ExecutionException))
            failure = failure.getCause();
        return failure instanceof ProviderClient.ProviderException ? failure.getMessage() : failure.getClass().getSimpleName();
    }

    private void refreshProviderStatus() {
        ProviderClient client = providerClient;
        if (client != null) client.requestStatusRefresh();
    }

    @Subscribe
    public void onSessionJoin(SessionJoinEvent event) {
        if (event.connection() instanceof org.geysermc.geyser.session.GeyserSession session
                && !session.getUpstream().getSession().isSubClient()) {
            gameOutcomes.joined(session.getUpstream().getSession().getPeer().getChannel());
        }
        refreshProviderStatus();
    }

    @Subscribe
    public void onSessionDisconnect(SessionDisconnectEvent event) {
        refreshProviderStatus();
    }

    @Subscribe
    public void onPostReload(GeyserPostReloadEvent event) {
        try {
            config = ConfigLoader.loadConfig(dataFolder().resolve("config.yml").toFile());
            refreshProviderStatus();
        } catch (IOException e) {
            logger().warning("Status configuration refresh failed; retaining previous configuration.");
        }
    }

    @Subscribe
    public void onGeyserShutdown(GeyserShutdownEvent event) {
        shutdown();
    }

    @Override
    public void disable() {
        shutdown();
        Extension.super.disable();
    }

    private void shutdown() {
        synchronized (providerLifecycle) {
            if (stopping) return;
            stopping = true;
            closeInbuiltResources();
            stopProvider();
        }
    }

    private void stopProvider() {
        synchronized (providerLifecycle) {
            if (providerShutdown != null) {
                providerShutdown.close();
                providerShutdown = null;
            } else closeNetworkResources();
            providerClient = null;
            wardenClaim = null;
        }
    }

    private void closeInbuiltResources() {
        if (inbuiltChannel != null) inbuiltChannel.close();
        if (signaling != null) signaling.close();
        if (inbuiltEventLoopGroup != null) inbuiltEventLoopGroup.shutdownGracefully();
    }

    private void closeNetworkResources() {
        if (this.netherNetChannel != null) {
            this.netherNetChannel.close();
        }
        if (this.eventLoopGroup != null) {
            this.eventLoopGroup.shutdownGracefully();
        }
    }
}
