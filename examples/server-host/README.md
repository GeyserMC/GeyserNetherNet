# Server-host provisioning

The Compose fragment points every purchased Geyser server at the host's provider
instead of Warden. Supply a per-customer token in `customer-provider-token`; the
container reads it from `/run/secrets` and registers the service directly into the
account represented by that token, with no PoW.

For anonymous registration, remove `NETHERNET_NXS_TOKEN`. Authorization is selected
automatically. The same five settings can be written in YAML; see
[provider-host.yml](../provider-host.yml).

The provider must advertise the selected protocol/profile and same-origin
operations. It does not need to implement Warden's account API: how it issues and
maps the customer token is provider-specific.

Set `NETHERNET_NXS_ADVERTISE_ADDRESSES` to a JSON list of reachable `IP:port`
strings and forward UDP 19133 to the container. Replace `/opt/geyser` with the
chosen image's Geyser working directory; persist `extensions/nethernet`.
