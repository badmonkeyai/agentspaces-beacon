# Security Policy

AgentSpaces Beacon serves one signed JSON document over HTTP, so a newcomer can
find a group and dial its seeds. The document carries public data only, signed
with the beacon peer's identity. The beacon adds nothing on the wire: it joins
the group as an ordinary member, and it inherits the security model of the
AgentSpaces core (signed, self-certifying peers and agents; the threat model in
the AgentSpaces specification, SPEC §11).

## Reporting a vulnerability

Please report vulnerabilities **privately** via GitHub's security advisories:

> https://github.com/badmonkeyai/agentspaces-beacon/security/advisories/new

A vulnerability in the beacon document model, its signature, or the `url`
locator that reads it belongs in the bootstrap repository's advisories:
https://github.com/badmonkeyai/agentspaces-bootstrap/security/advisories/new

A vulnerability in the AgentSpaces core libraries belongs in the core
repository's advisories:
https://github.com/badmonkeyai/agentspaces/security/advisories/new

Do not open public issues for suspected vulnerabilities, and do not include
exploit details in public discussions until a fix is released.

We will acknowledge your report within five business days, keep you informed of
progress, credit you in the advisory unless you prefer otherwise, and
coordinate the disclosure timeline with you. There is currently no bug bounty
program.

Questions can be sent to `oss [at] badmonkey.ai`

## Scope

In scope: anything in this repository that lets an attacker violate a guarantee
the beacon claims. For example:
- a document that leaks something beyond public data, such as an invite key,
  key material, or the address of a member that does not listen;
- a document the beacon serves with a signature a reader accepts, but with
  content the beacon's node did not produce;
- a request to the HTTP server that reads a path other than the well-known
  document, writes anything, or crashes the server;
- a way around the per-source rate limit, or a request pattern that exhausts
  the beacon's memory or threads;
- DNS records from `RecordPrinter` that point a reader somewhere other than
  the operator's beacon.

The beacon trusts its operator: whoever controls its keystore and its options
controls what it serves. TLS in front of the HTTP port is the operator's job.

## Supported versions

During 0.x, only the latest minor release receives security fixes.
