# Geyser NetherNet Extension
This is a Geyser extension that adds support for the NetherNet protocol via HTTP signaling.

This is a work in progress and is not yet ready for production use. The maintained branch builds the exact Network/native revisions recorded in the source manifests; see [PROVIDER.md](PROVIDER.md).

## Quick start

Start Geyser with the extension. The default `signalling: hybrid` starts inbuilt
HTTP signalling and registers with Warden. NXS uses Geyser's bind address and the
Bedrock port plus one. Set `nxs.advertise-addresses` for additional forwarded UDP
endpoints. The five settings, environment equivalents and fleet examples are in
[PROVIDER.md](PROVIDER.md).

## Provider game admission reporting
In provider mode, native ticket events establish transport progress. The extension additionally reports `ticket.game_rejected` when Geyser sends a Bedrock disconnect before play-ready admission, and `ticket.game_joined` on Geyser's primary-session `SessionJoinEvent`. An unsupported network protocol reports `unsupported_version`; other explicit rejections report `server_rejected`. Client closes and disconnects after joining are not game rejections.

These events use signed NXS `outcomes` batches and the channel's authenticated ticket ID. No disconnect text or player identity is included. Reporting retains only channel-owned flags and a bounded 256-event queue, with drops visible in `nethernet diagnostics`. Heartbeat declares `gameOutcomes: "available"`. Transport progress and game admission remain separate evidence.

## Provider check-in scheduling

The native provider follows the bounded schedule negotiated through NXS discovery
and heartbeat responses. The provider chooses its activity and lease policy.

Local player/status changes wake reporting without waiting for the idle timer.
Unchanged local observations do not send requests. Heartbeat carries the host
profile, installed admission keys and applied provider state; there is no separate
control poll or readiness request. A JVM shutdown hook gives the asynchronous provider
up to 20 seconds to finish its drain notification on normal process termination,
including SIGTERM. Extension shutdown itself remains asynchronous. Hosts
learn a changed policy on their next report; they cannot receive a revised schedule
while silent. The control plane must implement the advertised NXS lease contract.

## Inbuilt signalling

Use `signalling: inbuilt` for local HTTP signalling alone. The extension creates
its signing identity automatically and listens on Geyser's Bedrock address/port
over TCP. `hybrid` starts this listener alongside NXS; `none` disables both.
