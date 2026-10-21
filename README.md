# AgentSpaces Beacon

The beacon an operator runs to be found. A beacon is a member of the group
with stable reachability, usually the `RENDEZVOUS` role, that answers the
self-introduction and `GROUP_AD_WANT` as every member does, and serves beside
it the **beacon document**: one signed JSON document at
`/.well-known/agentspaces/beacon.json` that names, per group, the group id,
the signed founding advertisement, the seeds to dial, the wire hints, and the
multicast group. A newcomer's `url` locator (the `agentspaces-bootstrap`
project) reads it; a TXT record under the enterprise's domain points at it.
Nothing is added on the wire. A standalone project on the AgentSpaces 0.4.0
libraries.

## Coordinates

`ai.badmonkey.agentspaces:agentspaces-beacon:0.4.0`, depending on
`agentspaces-bootstrap` (the document model), `agentspaces-peering`, and the
core starter's framework-free wiring for the standalone main.

## What is in it

| Class | What |
|---|---|
| `BeaconDocumentSource` | builds the document from a running `PeerNode`: one entry per joined group, the founding when the node holds one, the seeds (this node first, then rendezvous members, then the rest, listening members only, capped), the node's wire hints, the multicast group; signed with the node's identity; rebuilt lazily at most once per refresh |
| `BeaconDocumentCustomizer` | a hook per group before signing: add a parameter, replace the seeds, set a delegation |
| `BeaconServer` | serves it on the JDK HTTP server: `GET` and `HEAD` of the well-known path only, `Cache-Control` from the TTL, `ETag` with `304`, read-only CORS, a per-source token bucket, an optional file copy for an ingress to serve |
| `BeaconPublisher` | the seam a host's own web layer implements instead of `BeaconServer` |
| `RecordPrinter` | the DNS-SD PTR, SRV, and TXT set and the WPAD-style TXT pointer, rendered as a zone fragment |
| `BeaconMain` | the standalone beacon |

## Running a beacon

The jar names `BeaconMain` as its main class but carries no dependencies, so
copy them beside it and start it with a classpath:

```
mvn -q clean package
mvn -q -pl beacon dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
java -cp 'beacon/target/agentspaces-beacon-0.4.0.jar:beacon/target/lib/*' \
     ai.badmonkey.agentspaces.beacon.BeaconMain --keystore ./beacon-keys --founding research-fleet-v1 \
     --bind 0.0.0.0:7500 --advertise beacon.corp.example:7500 --http 0.0.0.0:7580 \
     --roles rendezvous --domain corp.example \
     --url https://agentspaces.corp.example/.well-known/agentspaces/beacon.json
```

Options: `--keystore`, `--founding <string>` or `--join <aspace://id>`, `--name`,
`--seeds a:1,b:2`, `--bind`, `--advertise`, `--http host:port` (default
`0.0.0.0:7580`), `--roles rendezvous,relay`, `--no-tls`, `--membership open|invite`,
`--domain` (the zone to print records for), `--url` (the public document URL),
`--write-to <file>`, `--ttl <seconds>`, `--refresh <seconds>`, `--max-seeds <n>`,
`--rate <per-second>`, `--multicast host:port`.

At start it prints, for the operator's DNS team:

```
register in corp.example:
  _agentspaces._tcp.corp.example.                       IN PTR  research-fleet._agentspaces._tcp.corp.example.
  research-fleet._agentspaces._tcp.corp.example.        IN SRV  10 50 7500 beacon.corp.example.
  research-fleet._agentspaces._tcp.corp.example.        IN TXT  "v=1" "group=research-fleet" "id=aspace://z7f3a..." "scheme=tls" "url=https://agentspaces.corp.example/.well-known/agentspaces/beacon.json"
  agentspaces.corp.example.                             IN TXT  "v=1" "url=https://agentspaces.corp.example/.well-known/agentspaces/beacon.json"
```

Put TLS termination in front of the HTTP port; the document is public data
and carries no secret, but a reader trusts the hostname.

## In an application

```java
BeaconDocumentSource source = new BeaconDocumentSource(node, identity, InstantSource.system(),
        Duration.ofMinutes(5), Duration.ofSeconds(30), 8, null,
        List.of((draft, group) -> draft.parameter("region", "eu-west-1")));
BeaconServer server = new BeaconServer(source, 50, Path.of("/var/lib/agentspaces/beacon.json"));
server.start(new InetSocketAddress("0.0.0.0", 7580));
```

The Spring Boot and Micronaut adapters (`agentspaces.beacon.*`) are not built
yet; the classes above are what they would wire.

## Building

```
mvn -B -ntp verify
```

The tests start a real peer on loopback and serve its document over HTTP; they
need no network beyond the machine. The AgentSpaces 0.4.0 artifacts, including
`agentspaces-bootstrap`, resolve from Maven Central, or from the local
repository after `mvn install` in those projects.

## Releasing

The `central-publish` profile attaches the sources and javadoc jars, signs
every artifact with GPG (the passphrase from `MAVEN_GPG_PASSPHRASE`, or a GPG
agent), and uploads through the Sonatype Central Portal using the `central`
server id in `settings.xml`:

```sh
mvn -Pcentral-publish -DskipTests -Dgpg.skip=true clean package   # dry run
mvn -Pcentral-publish clean deploy                                # release
```

`autoPublish` is off, so the upload waits on the Portal as a validated bundle
until you publish it there. The bundle holds `agentspaces-beacon` and its
parent POM, `agentspaces-beacon-parent`. Publish `agentspaces-bootstrap` 0.4.0
first, since the beacon depends on it. CI runs the same profile unsigned
(`-Dgpg.skip=true`) on every push, so a javadoc or packaging problem fails a
pull request before it can fail a release.

## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and the
[code of conduct](CODE_OF_CONDUCT.md). Report a vulnerability privately, as
[SECURITY.md](SECURITY.md) describes.

## Licence

Apache License 2.0, Copyright 2026 Bad Monkey, Inc. See `LICENSE` and `NOTICE`.
