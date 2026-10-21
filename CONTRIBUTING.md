# Contributing to AgentSpaces Beacon

Thank you for your interest in contributing. AgentSpaces Beacon is an
Apache 2.0 project from Bad Monkey, Inc., and everything in this repository
stays Apache 2.0.

This repository holds the beacon an operator runs to be found: the signed
beacon document a peer serves at `/.well-known/agentspaces/beacon.json`, the
HTTP server that serves it, the DNS records it prints, and a standalone main.
The document model and the `url` locator that reads it live in
[agentspaces-bootstrap](https://github.com/badmonkeyai/agentspaces-bootstrap);
the coordination fabric itself lives in the
[AgentSpaces core repository](https://github.com/badmonkeyai/agentspaces).

## Before you start

- **Questions and ideas** are welcome as [GitHub issues](https://github.com/badmonkeyai/agentspaces-beacon/issues).
- **Security issues** must not be filed as public issues. See
  [SECURITY.md](SECURITY.md) for private reporting.
- **Code of conduct:** by participating you agree to the
  [code of conduct](CODE_OF_CONDUCT.md).

## The rules a change must keep

- **Nothing new on the wire.** A beacon answers the self-introduction and
  `GROUP_AD_WANT` as every member does. The document is the only thing it
  adds, and it travels over HTTP. A change that needs a new frame or entry
  type is a ticket against the core.
- **The document shape belongs to agentspaces-bootstrap.** `BeaconDocument`
  and its signature live there, so a reader and a beacon on different
  releases agree on the bytes. Change the shape there first.
- **The document carries no secret.** It names public data a newcomer needs
  to dial in. A field that would leak an invite key, a private key, or a
  member's private address does not belong in it.
- **The server stays small and read-only.** `GET` and `HEAD` of the
  well-known path, with cache headers, `ETag`, read-only CORS, and the rate
  limit. A host that wants more serves the document through its own web layer
  with `BeaconPublisher`.

## Building and testing

Build the module and run its tests:

```bash
mvn -B -ntp verify
```

Run the release dry run, as CI runs it:

```bash
mvn -B -ntp -Pcentral-publish -DskipTests -Dgpg.skip=true package
```

Java 21 and Maven. The tests start a real peer on loopback and read its
document over HTTP. Give every test that starts a peer a timeout, and close
the peer and the server when the test ends.

## Pull requests

Open an issue first for anything beyond a fix. Keep the change small, add the
test that fails without it, and sign off your commits (DCO). CI runs the build
and a dependency scan on every push and pull request.
