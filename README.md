# AgentSpaces Beacon

A **beacon** is an AgentSpaces peer that stays up at a known address so that newcomers have somewhere to go. This project runs one. It joins your group like any other peer and publishes a signed document that lists the group and its current seeds. Agents on a Spring Boot or Micronaut host can then find the group from DNS or from a URL, and Python and TypeScript agents can read the document at its URL, with no seed list in their configuration.

You can run the beacon as a standalone program, or embed its classes in your own application to build a beacon of your own. If peer, group, seed, locator, and chain are new words, read [The ideas, one at a time](https://github.com/badmonkeyai/agentspaces-bootstrap#the-ideas-one-at-a-time) in the `agentspaces-bootstrap` README first. This README uses the same running example: Acme Corp's `research-fleet`.

## What a beacon is

A newcomer to a group has to dial one member before the fabric can do anything else, and the group cannot tell it which one. A beacon answers that question. It has three parts:

- **It is a member of the group.** It has a stable address and usually plays the `RENDEZVOUS` role, a peer that caches many members' advertisements and answers discovery queries. It answers self-introductions and `GROUP_AD_WANT` requests as every member does. Nothing in the group protocol marks a peer as a beacon, so the beacon adds nothing to the wire.
- **It publishes the beacon document.** The document is one signed JSON file at `/.well-known/agentspaces/beacon.json`. For each group the beacon has joined, it lists the name, the id, the seeds to dial, the wire hints, and the multicast group when one is configured. When the group has a signed founding, the document carries that too.
- **It prints the DNS records that point to the document.** At startup the beacon prints a zone fragment with the records for the document and for its seeds. Your DNS team registers them, and a newcomer's `dns-sd` and `dns-txt` locators find them.

The beacon holds no secret: the document never carries a content key, a credential, or a membership hint. The group's own admission policy decides who joins, as it does for every member. You register the DNS records the beacon prints, because the beacon does not change your DNS.

## An example: Acme's fleet gets a beacon

Acme runs `research-fleet`, a group of agents on servers, in Kubernetes, and on engineers' laptops. Acme started the group from the text `research-fleet-v1`, and its network domain is `corp.example`. Acme wants a new agent to find the fleet without anyone editing a seed list.

**1. Start the beacon.** On a server with a stable address, build the project, copy the dependencies beside the jar, and start `BeaconMain`. The jar carries no dependencies, so the start command uses a classpath:

```sh
mvn -q clean package
mvn -q -pl beacon dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib
java -cp 'beacon/target/agentspaces-beacon-0.4.0.jar:beacon/target/lib/*' \
     ai.badmonkey.agentspaces.beacon.BeaconMain --keystore ./beacon-keys --founding research-fleet-v1 \
     --name research-fleet --bind 0.0.0.0:7500 --advertise beacon.corp.example:7500 \
     --http 0.0.0.0:7580 --roles rendezvous --domain corp.example \
     --url https://agentspaces.corp.example/.well-known/agentspaces/beacon.json
```

The beacon starts the group from the same text as every other Acme peer, plays the rendezvous role, advertises itself as `beacon.corp.example:7500`, and serves the document on port 7580. The `--keystore` directory keeps the beacon's identity between restarts, which matters because that identity signs the document.

**2. Register the DNS records.** Because of `--domain` and `--url`, the beacon prints what the DNS team registers:

```
beacon: peer peer:zDnaCm6XCa… serving 1 group(s) at http://localhost:7580/.well-known/agentspaces/beacon.json
beacon: research-fleet aspace://zDXTnY2y... seeds 1
register in corp.example:
_agentspaces._tcp.corp.example.                          IN PTR  research-fleet._agentspaces._tcp.corp.example.
research-fleet._agentspaces._tcp.corp.example.           IN SRV  10 50 7500 beacon.corp.example.
research-fleet._agentspaces._tcp.corp.example.           IN TXT  "v=1" "group=research-fleet" "id=aspace://zDXTnY2y..." "scheme=tls" "url=https://agentspaces.corp.example/.well-known/agentspaces/beacon.json"
agentspaces.corp.example.                                IN TXT  "v=1" "url=https://agentspaces.corp.example/.well-known/agentspaces/beacon.json"
```

(The fragment also starts with a comment line naming the zone.) The PTR record names the group's instance under `_agentspaces._tcp`. Each SRV record gives a seed's host and port, and the TXT record carries the group name, the id, the transport scheme, and the document's URL. The last record, at `agentspaces.corp.example`, points at the document alone, for networks that prefer to publish one record.

**3. Serve the document over TLS.** Put TLS termination in front of port 7580. The document is public data and carries no secret, but a reader trusts the hostname. This is what a reader gets:

```sh
curl -s https://agentspaces.corp.example/.well-known/agentspaces/beacon.json
```

```json
{
  "v": 1,
  "issuer": "zDnaCm6XCa...",
  "issuerKey": "<base64 Ed25519 public key>",
  "issued": "2026-10-21T19:35:30Z",
  "ttl": 300,
  "groups": [
    {
      "name": "research-fleet",
      "id": "aspace://zDXTnY2y...",
      "seeds": [
        { "transport": "tls", "address": "beacon.corp.example:7500", "priority": 0 },
        { "transport": "tls", "address": "seed-a.corp.example:7500", "priority": 1 },
        { "transport": "tls", "address": "seed-b.corp.example:7500", "priority": 2 }
      ],
      "wire": { "batch": 1, "hashes": 1, "tree": 1 }
    }
  ],
  "signature": "<base64 Ed25519 signature over every other field>"
}
```

The response carries `Cache-Control: public, max-age=300` (the document's `ttl`), an `ETag` that answers `If-None-Match` with `304`, and read-only CORS headers, so a browser tool can read it. The beacon answers only `GET` and `HEAD` at that path, and it limits each source address to 50 requests a second by default.

**4. Point an agent at the fleet.** An agent that joins `research-fleet` needs the group's text and a way to look for it, and no seeds:

```yaml
agentspaces:
  groups:
    - name: research-fleet
      founding: research-fleet-v1
      bootstrap:
        order: [ dns-sd ]
```

`summarizer` starts on a laptop on Acme's Wi-Fi. DHCP gives the laptop the search domain `corp.example`, so the `dns-sd` locator lists the instances under `_agentspaces._tcp.corp.example`, follows the TXT record's `url=` to the document, and receives the seeds. `summarizer` dials them and joins. The [`agentspaces-bootstrap` README](https://github.com/badmonkeyai/agentspaces-bootstrap) describes every locator and setting.

**5. The document follows the fleet.** The beacon builds its seed list from its live view of the group: itself first, then the members with the rendezvous role, then the other members that listen for connections, up to `--max-seeds` (8 by default). When `seed-a` and `seed-b` join, the next rebuild lists them, as the document above shows. The beacon rebuilds at most once every `--refresh` seconds (30 by default). A member that only dials out, such as a laptop, has no address to list and never appears.

The DNS records do not follow the fleet. Registered by hand, the SRV records name the seeds that existed on the day someone typed them:

```
research-fleet._agentspaces._tcp.corp.example.           IN SRV  10 50 7500 beacon.corp.example.
research-fleet._agentspaces._tcp.corp.example.           IN SRV  20 50 7500 seed-a.corp.example.
research-fleet._agentspaces._tcp.corp.example.           IN SRV  30 50 7500 seed-b.corp.example.
```

If your seed set changes often, register only the pointer record at `agentspaces.corp.example` and let the document carry the seeds. A newcomer then uses the `dns-txt` locator, which reads that one record and fetches the document.

## What a beacon does for the agents you build

An AgentSpaces agent is a peer, and every peer needs a first address to dial. A beacon moves that address out of every agent's configuration and into one place that an operator maintains.

- **Agents carry a group name and a place to look.** A new agent needs `founding: research-fleet-v1` (or, for a group with a signed founding, its group id) and one `bootstrap` setting. You deploy a hundred agents with the same small configuration, on servers, in pods, and on laptops, and none of them holds a seed list that can go stale.
- **The seed list follows the fleet.** Kubernetes reschedules a pod and its address changes. The beacon's next document lists the new address, and newcomers receive it.
- **Every language reads the same document.** The `url` and `document` locators in `agentspaces-bootstrap` read it for Spring Boot and Micronaut hosts. The Python and TypeScript clients fetch it, verify its signature, and take its seeds. Their one-call `bootstrap(url)` joins a group whose entry carries a signed founding, and the entry of a group started from a text carries none.
- **Other organizations join with an id and a URL.** For a group with a signed founding, a Python or TypeScript agent needs only the group id and the document's URL. A Spring Boot or Micronaut agent also lists one seed, the beacon's address, because those hosts start a `join:` group only when it has a configured seed or a `BeaconLocator` bean. Each agent checks the founding against the id, and pins the beacon's PeerId with `beacon-peer` when it wants to refuse a replaced document.
- **A lost beacon slows newcomers and nothing else.** A member that has joined learns the rest of the group by gossip and does not need the beacon. Run a second beacon for availability: it joins the same group, and each beacon's document lists the other as a seed because the other is a member with the rendezvous role.

## Building your own beacon

The standalone beacon is a small program over four classes, and you can assemble the same pieces inside your own application. You start with a running `PeerNode` that has joined your groups, and the node's `PeerIdentity`. Keep that identity in a keystore, so the beacon's PeerId survives a restart. The node can be the one that runs your agents or a dedicated one. A dedicated node is usually easier to keep at a stable address.

```java
BeaconDocumentSource source = BeaconDocumentSource.of(node, identity);
BeaconServer server = new BeaconServer(source, 50, Path.of("/var/lib/agentspaces/beacon.json"));
int port = server.start(new InetSocketAddress("0.0.0.0", 7580));
System.out.print(RecordPrinter.zone(source.get(), "corp.example",
        "https://agentspaces.corp.example" + BeaconDocument.WELL_KNOWN));
```

- `BeaconDocumentSource.of` builds a signed document from the node's groups, with a 5-minute TTL, a rebuild at most every 30 seconds, and 8 seeds per group. The full constructor sets each of those, and takes the multicast group to advertise and a list of customizers.
- `BeaconServer` serves the document on the JDK HTTP server. Its second argument is the per-source request rate, and the third is an optional file that the beacon replaces atomically each time a request, or startup, builds a changed document. Nothing refreshes the file on a timer, so an ingress or a CDN that serves only the file sees a new document after the next request to the beacon's port.
- `RecordPrinter.zone` renders the DNS records for a document, for your DNS team or for your own DNS automation.

A `BeaconDocumentCustomizer` adjusts each group's entry before the beacon signs it. This one adds a `region` parameter that readers receive in the hint:

```java
BeaconDocumentSource source = new BeaconDocumentSource(node, identity, InstantSource.system(),
        Duration.ofMinutes(5), Duration.ofSeconds(30), 8, null,
        List.of((draft, group) -> draft.parameter("region", "eu-west-1")));
```

To serve the document through a web layer you already run, such as Spring MVC or Micronaut HTTP, skip `BeaconServer` and return `source.get().toBytes()`, the signed JSON, at `BeaconDocument.WELL_KNOWN`. `BeaconPublisher` is the interface a web layer implements to take the server's place. The Spring Boot and Micronaut adapters (`agentspaces.beacon.*`) are not built yet, so an application on either framework wires these classes itself.

## The standalone beacon's options

| Option | Default | What it does |
|---|---|---|
| `--keystore <dir>` | none, which makes a new identity at every start | where the beacon keeps its identity keys. Always set it: the beacon's PeerId signs the document, and readers that pin the PeerId with `beacon-peer` refuse a beacon whose id changed |
| `--founding <text>` | none | starts the group from a text, as every other peer of a text group does |
| `--join <aspace://id>` | none | joins a group by id, which needs a seed that serves the signed founding |
| `--name <name>` | the founding text, else `fleet` | the group's name |
| `--seeds a:1,b:2` | none | addresses to dial in order to join |
| `--bind <host:port>` | `0.0.0.0:7500` | where the peer listens |
| `--advertise <host:port>` | the bind address | the address other peers dial, carried in the beacon's advertisement |
| `--http <host:port>` | `0.0.0.0:7580` | where the document is served |
| `--roles rendezvous,relay` | `rendezvous` | the roles the peer plays |
| `--no-tls` | TLS on | turns TLS off for the peer's connections |
| `--membership open\|invite` | `open` | the group's membership policy |
| `--domain <zone>` | none | the DNS zone to print records for |
| `--url <url>` | the local server's URL | the document's public URL, printed in the records |
| `--write-to <file>` | none | keeps a copy of the document in this file, rewritten when a request or startup builds a changed document |
| `--ttl <seconds>` | 300 | the document's time to live |
| `--refresh <seconds>` | 30 | the most often the document is rebuilt |
| `--max-seeds <n>` | 8 | the seed limit per group |
| `--rate <per-second>` | 50 | requests per second per source address |
| `--multicast <host:port>` | none | turns on the multicast beacon and lists its group in the document |

## Coordinates

`ai.badmonkey.agentspaces:agentspaces-beacon:0.4.0`, depending on
`agentspaces-bootstrap` (the document model), `agentspaces-peering`, and the
core starter's framework-free wiring for the standalone main.

## What is in it

| Class | What |
|---|---|
| `BeaconDocumentSource` | builds the document from a running `PeerNode`: one entry per joined group, the founding when the group has one, the seeds (this node first, then rendezvous members, then the rest, listening members only, capped), the node's wire hints, the multicast group; signed with the node's identity; rebuilt lazily at most once per refresh |
| `BeaconDocumentCustomizer` | a hook per group before signing: add a parameter, replace the seeds, or change the entry's other fields |
| `BeaconServer` | serves the document on the JDK HTTP server: `GET` and `HEAD` of the well-known path only, `Cache-Control` from the TTL, `ETag` with `304`, read-only CORS, a per-source token bucket, an optional file copy for an ingress to serve |
| `BeaconPublisher` | the interface a host's own web layer implements instead of `BeaconServer` |
| `RecordPrinter` | the DNS-SD PTR, SRV, and TXT set and the WPAD-style TXT pointer, rendered as a zone fragment |
| `BeaconMain` | the standalone beacon |

## Building

```
mvn -B -ntp verify
```

The tests start a real peer on loopback and serve its document over HTTP; they
need no network beyond the machine. The AgentSpaces 0.4.0 artifacts, including
`agentspaces-bootstrap`, resolve from Maven Central, or from the local
repository after `mvn install` in those projects.


## Contributing and security

See [CONTRIBUTING.md](CONTRIBUTING.md) and the
[code of conduct](CODE_OF_CONDUCT.md). Report a vulnerability privately, as
[SECURITY.md](SECURITY.md) describes.

## Licence

Apache License 2.0, Copyright 2026 Bad Monkey, Inc. See `LICENSE` and `NOTICE`.
