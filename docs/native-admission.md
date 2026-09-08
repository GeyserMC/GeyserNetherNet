# Native provider mode (experimental)

Provider mode accepts NetherNet connections on a shared UDP socket and passes
accepted connections into Geyser's existing Bedrock pipeline. The extension
creates or validates `host-cert.pem` and `host-key.pem` in
`extensions/nethernet/provider-state`. It publishes the certificate fingerprint, a reachable
UDP candidate and a fresh endpoint incarnation. Admission keys arrive through
background registration and key updates.

## How a connection is accepted

1. libjuice recognises an incoming STUN Binding request and retains its first
   packet in a bounded queue. The packet has not yet been authenticated.
2. Java receives the request ID, ICE username fragments and source address.
   Network validates the NXS token using its locally installed admission key and
   reserves connection capacity. It makes no request to the provider.
3. libdatachannel asks libjuice to verify STUN integrity before creating a peer.
   Geyser installs the peer's callbacks and checks that its certificate matches
   the published host identity.
4. Native code attaches the peer and processes the retained first request. The
   client does not need to retransmit it for acceptance to finish. DTLS checks
   the client's expected certificate fingerprint before data channels open.

Duplicate pending requests share one admission notification. Packet bytes stay
native throughout this flow; there is no Java packet callback or packet replay
API. Established ICE, DTLS and SCTP traffic stays native. Java receives the normal
application messages delivered through the data channels.

Token expiry prevents new admission. It does not disconnect an established
session. A used token cannot allocate another session. Failed STUN authentication
releases an unused token reservation so a forged packet does not consume the
real client's token.

## Current limits

The extension's host factory uses `AdmissionGate.Limits.defaults()` from Network:

| Resource or deadline | Limit |
| --- | --- |
| Retained native requests | 1024 |
| Packet bytes retained per request | 2048 bytes, plus request metadata |
| Time to accept a retained request | 15 seconds |
| Session reservations, including peers still closing | 1024 |
| Tracked token claims, including active and recently used tokens | 8192 |
| Time for both NetherNet data channels to open after reservation | 15 seconds |

These are transport limits. Geyser's maximum player count supplies the separate capacity advertised
to the provider. Network supplies the limits above to the native listener; the
standalone library defaults of 256 pending requests and 5 seconds do not apply
here.

Timeout and cancellation release a session reservation only after native teardown
finishes. Repeated pending-limit rejections produce one aggregate warning at most
every five seconds. `nethernet diagnostics` reports admission and native counters;
it does not establish that a player reached gameplay.

## Configure a test instance

Set `signalling: nxs` (or use the `hybrid` default). NXS binds Geyser's effective
Bedrock address and port plus one. Set `nxs.advertise-addresses` to additional
reachable numeric IPv4/IPv6 endpoints when forwarding is required. Profile and
authorization are automatic. See [configuration](../PROVIDER.md).

## Build and verify the dependency chain

```sh
bash scripts/build-native-development.sh
```

Run this from a clean, committed checkout after updating the source pins. The
script checks out the pinned Network revision, builds its pinned Java and native
dependencies, runs their tests and builds the extension. It checks the packaged
host factory, the single native library and every source revision in the JAR
manifest. It also compares the packaged native bytes with the built artifact and
writes the revisions and extension hash to `build/provenance.json`.

The build requires Linux x86_64, JDK 8/17/21 toolchains, Python 3, OpenSSL development
headers, CMake, Git and a C++ compiler. The current development artifact uses
system OpenSSL and the build host's ABI. Other platforms need their own builds
and validation.

For coordinated local builds, set `NXS_NETWORK_SOURCE` to the clean pinned Network
repository and `NATIVE_JAVA_CHECKOUT` to the clean pinned Java/native checkout.
The same committed revision checks still apply. The script builds artifacts;
it does not merge, push or deploy repositories.

A successful build and native connection test establish transport behaviour.
Verification on the test server must also record a stock-client game join,
reconnect and direct-join behaviour. Two-host routing needs a separate test when
that setup is used.
