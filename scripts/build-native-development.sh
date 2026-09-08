#!/usr/bin/env bash
set -euo pipefail
# Rebuild an immutable source chain. This script never merges or publishes sources.
extension_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
network_revision=$(sed -n 's/^commit=//p' "$extension_root/registration-network.properties")
[[ $network_revision =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid Network source pin' >&2; exit 1; }
[[ -z $(git -C "$extension_root" status --porcelain) ]] || { echo 'Refusing to package a dirty extension checkout' >&2; exit 1; }
extension_revision=$(git -C "$extension_root" rev-parse HEAD)
integration_root=$(mktemp -d "${TMPDIR:-/tmp}/geyser-native-build.XXXXXXXX")
trap 'rm -rf -- "$integration_root"' EXIT
network_source=${NXS_NETWORK_SOURCE:-https://github.com/teamziax/NetworkCompatible.git}
git clone --quiet --no-checkout "$network_source" "$integration_root/network"
git -C "$integration_root/network" checkout --quiet --detach "$network_revision"
[[ $(git -C "$integration_root/network" rev-parse HEAD) == "$network_revision" ]] || exit 1
python3 - "$extension_root/native-dependencies.properties" "$integration_root/network/native-dependencies.properties" <<'PY'
import pathlib, re, sys
def properties(path):
    return dict(line.split('=', 1) for line in pathlib.Path(path).read_text().splitlines() if line and not line.startswith('#'))
extension, network = map(properties, sys.argv[1:])
for local, external in [('javaCommit', 'java.commit'), ('datachannelCommit', 'datachannel.commit'), ('juiceCommit', 'juice.commit')]:
    if not re.fullmatch('[0-9a-f]{40}', extension[local]) or extension[local] != network[external]:
        raise SystemExit('Native source pin mismatch: ' + local)
PY
maven_root="$integration_root/maven"
bash "$integration_root/network/scripts/bootstrap-native-admission.sh" "$maven_root"
(
    cd "$integration_root/network"
    ./gradlew :external-signalling:test :external-signalling:nativeAdmissionTest :transport-nethernet:test \
        --no-daemon --max-workers=2 -PnativeMavenRepository="$maven_root"
)
cd "$extension_root"
bash gradlew build --no-daemon --max-workers=2 -PnetworkPath="$integration_root/network" \
    -PnativeMavenRepository="$maven_root" -PregistrationRevision="$extension_revision"
python3 - "$extension_root" "$network_revision" "$extension_revision" "$maven_root" <<'PY'
import hashlib, json, pathlib, sys, zipfile
root, network, extension, maven = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3], pathlib.Path(sys.argv[4])
pins = dict(line.split('=', 1) for line in (root / 'native-dependencies.properties').read_text().splitlines() if line and not line.startswith('#'))
version = '0.24.5.0-dev.' + pins['javaCommit']
artifact = maven / 'io/github/teamziax/libdatachannel-java' / version
native_provenance = json.loads((artifact / 'provenance.json').read_text())
for field, pin in [('bindingRevision', 'javaCommit'), ('libdatachannelRevision', 'datachannelCommit'), ('libjuiceRevision', 'juiceCommit')]:
    assert native_provenance[field] == pins[pin], 'Native provenance mismatch: ' + field
jars = list((root / 'build/libs').glob('*.jar'))
assert len(jars) == 1, 'Expected one shaded extension'
with zipfile.ZipFile(jars[0]) as jar, zipfile.ZipFile(artifact / f'libdatachannel-java-{version}-x86_64.jar') as native:
    service = 'META-INF/services/org.geyser.extension.nethernet.provider.ProviderHostFactory'
    assert jar.read(service).decode().strip() == 'org.geyser.extension.nethernet.admission.NativeProviderHostFactory'
    assert jar.namelist().count('native/libdatachannel-java.so') == 1
    assert jar.read('native/libdatachannel-java.so') == native.read('native/libdatachannel-java.so'), 'Packaged native bytes differ'
    manifest = jar.read('META-INF/MANIFEST.MF').decode().replace('\r\n ', '')
    for name, sha in [('Registration-Revision', extension), ('Extension-Revision', extension), ('Native-Network-Revision', network),
                      ('Registration-Network-Revision', network), ('Native-JNI-Revision', pins['javaCommit']),
                      ('Native-Datachannel-Revision', pins['datachannelCommit']), ('Native-Juice-Revision', pins['juiceCommit'])]:
        assert name + ': ' + sha in manifest, 'Manifest pin mismatch: ' + name
provenance = {'extensionRevision': extension, 'networkRevision': network, 'native': native_provenance,
              'artifact': jars[0].name, 'sha256': hashlib.sha256(jars[0].read_bytes()).hexdigest()}
(root / 'build/provenance.json').write_text(json.dumps(provenance, indent=2) + '\n')
print('Native extension packaging PASS:', jars[0])
PY
