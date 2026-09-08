# Signalling configuration

The extension generates this entire configuration in `extensions/nethernet/config.yml`:

```yaml
signalling: hybrid
nxs:
  advertise-addresses: []
  token: ''
  endpoint: https://agent.warden.cloud
  data: {}
```

| Setting | Behavior |
| --- | --- |
| `signalling` | `inbuilt` runs local HTTP signalling; `nxs` registers with the external provider; `hybrid` runs both (default); `none` disables both. Geyser's RakNet listener continues in every mode. |
| `nxs.advertise-addresses` | Additional numeric `IPv4:port` or `[IPv6]:port` endpoints, added to suitable bound interface addresses. Duplicates are removed. |
| `nxs.token` | Opaque bearer token, or `file:/path/to/token`. Empty selects anonymous proof of work. |
| `nxs.endpoint` | Provider HTTPS origin. Defaults to Warden. HTTP is accepted only for loopback development providers. |
| `nxs.data` | String key/value registration metadata. `region` and `pool` select placement; other keys become tags. |

All changes require a restart. There are no config migrations or legacy aliases.

## Listener and identity defaults

Inbuilt HTTP uses Geyser's effective Bedrock bind address and port over TCP.
If that port matches the Java server port, it is skipped with a warning. Other
bind failures are reported without preventing NXS startup. Each listener owns
its resources; an NXS startup failure leaves inbuilt signalling running.

NXS inherits the same bind address and uses the effective Bedrock port **plus one**
over UDP, after Geyser has resolved any port cloning. A Bedrock port of 65535 cannot
supply that additional port. Routing capacity comes from Geyser's advertised maximum
players. Protocol profile, authorization and registration mode are selected internally.

A concrete bind publishes that suitable address. An IPv4 wildcard discovers IPv4
addresses; the native dual-stack `::` wildcard discovers IPv4 and IPv6 addresses.
Interface changes are picked up during background check-ins. To publish additional
forwarded addresses:

```yaml
nxs:
  advertise-addresses:
    - '203.0.113.10:29133'
    - '[2001:db8::10]:39133'
```

Replace the documentation addresses with real reachable addresses and arrange UDP
forwarding to the listener. The combined discovered/configured set is limited to
32 endpoints. Wildcard, loopback, link-local, multicast and reserved addresses
are excluded for external providers. Discovery cannot prove public reachability
or configure forwarding. Startup warns for private-only or IPv6-only endpoints.
Private endpoints require LAN/VPN routing; Warden does not relay game traffic.

The extension automatically creates a private `identity.p12` for inbuilt signing
and a `provider-state` directory for the NXS registration and DTLS key/certificate.
Both live inside `extensions/nethernet`. Preserve this directory on restart and
mount a separate persistent directory for each live replica. Existing identity
files are validated and reused. Missing or invalid key pairs fail startup.

## Tokens and metadata

A token's authority determines whether to create a service in an account or attach
to an existing service. The provider selects and signs the concrete registration
mode; the host does not guess from the opaque token. No token creates a new service
using the advertised proof-of-work challenge.

`nxs.data` is immutable registration metadata. For example:

```yaml
nxs:
  token: file:/run/secrets/nxs-token
  data:
    region: EU
    pool: proxy
    location: london
    role: game-proxy
```

When any metadata is supplied, omitted region/pool values become `global`/`default`.
An empty map sends no placement. Warden permits metadata on anonymous new services;
it grants no authority over another service. Fleet tokens must be delegated the
exact region, pool and tags. An absent or mismatched fleet placement is rejected.
Account-scoped `provider_service:create` tokens create services; service-scoped
`game_server_bootstrap:write` tokens attach instances. Those scope names belong
to Warden; independent providers can issue their own opaque credentials.

A bare token is used directly. `file:` accepts an absolute path or a path relative
to the extension directory. Absolute paths and paths starting `./` or `../` also
select a file. Missing, empty, oversized or multiline token files fail closed.
Tokens are sent only to registration over the validated same-origin connection;
they are not copied into durable registration state or diagnostic strings.

## Environment variables

Configurate parses YAML and maps typed values. Its built-in environment source
configures **loader options**, not arbitrary application fields. This extension
applies one Configurate overlay after loading/saving, so environment values override
YAML without being written back. The five corresponding variables are:

```sh
NETHERNET_SIGNALLING=hybrid
NETHERNET_NXS_ADVERTISE_ADDRESSES='["1.1.1.1:29133", "[2606:4700:4700::1111]:39133"]'
NETHERNET_NXS_TOKEN=file:/run/secrets/nxs-token
NETHERNET_NXS_ENDPOINT=https://agent.warden.cloud
NETHERNET_NXS_DATA='{"region":"EU","pool":"proxy","location":"london"}'
```

The list and map accept YAML or JSON; their environment value replaces the entire
configured collection. Strings are literal. An empty token disables configured
bearer authentication. See [Geyser's ConfigLoader](https://github.com/GeyserMC/Geyser/blob/master/core/src/main/java/org/geysermc/geyser/configuration/ConfigLoader.java)
and [Configurate's environment source](https://github.com/GeyserMC/Configurate/blob/master/core/src/main/java/org/spongepowered/configurate/loader/LoaderOptionSources.java).

## Lifecycle and examples

NXS follows the provider's heartbeat schedule, publishes Geyser status and reports
ticket-correlated transport/game outcomes. Graceful shutdown drains the provider
before closing its native endpoint. Inbuilt signalling closes independently.
`nethernet diagnostics` reports native counters and signed readiness. Optional
Warden ownership actions appear only through `cloud.warden.claim`; `nethernet claim`
explicitly refreshes the action. Independent providers need no Warden account API.

Examples cover [anonymous NXS](examples/provider-local.yml),
[a server host](examples/server-host/README.md) and
[a Kubernetes fleet](examples/kubernetes-proxy-fleet/README.md).
The [NXS contract](https://github.com/teamziax/NetworkCompatible/blob/nxs-dev/docs/external-signalling/README.md)
and [native build instructions](docs/native-admission.md) describe the maintained
source chain. Native/fixture readiness does not establish stock-client gameplay.
