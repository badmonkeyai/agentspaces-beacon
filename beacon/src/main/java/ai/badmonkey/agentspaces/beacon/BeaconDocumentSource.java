/*
 * Copyright 2026 Bad Monkey, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.badmonkey.agentspaces.beacon;

import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.bootstrap.BeaconDocument;
import ai.badmonkey.agentspaces.common.codec.CborCodec;
import ai.badmonkey.agentspaces.common.id.PeerId;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.peering.membership.GroupMembership;
import ai.badmonkey.agentspaces.peering.node.GroupRuntime;
import ai.badmonkey.agentspaces.peering.node.PeerNode;

import java.time.Duration;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Builds the beacon document from a running node (SRAD DB-B-01, DB-B-05):
 * one entry per joined group with the group's id, its signed founding
 * advertisement when the node holds one, the seeds (this node first, then
 * members with the {@code RENDEZVOUS} role, then the rest, listening members
 * only, capped), this node's wire hints, the multicast group when one is
 * configured, and whatever the customizers add; then signed by the node's
 * identity. Rebuilt lazily at most once per refresh interval.
 */
public final class BeaconDocumentSource implements Supplier<BeaconDocument> {

    private final PeerNode node;
    private final PeerIdentity identity;
    private final InstantSource clock;
    private final Duration ttl;
    private final Duration refresh;
    private final int maxSeeds;
    private final String multicast;
    private final List<BeaconDocumentCustomizer> customizers;
    private final CborCodec codec = CborCodec.defaultCodec();
    private volatile BeaconDocument cached;
    private volatile long builtMillis = Long.MIN_VALUE;

    /**
     * A source.
     *
     * @param node        the node
     * @param identity    the signing identity (the node's)
     * @param clock       the clock
     * @param ttl         the document's time to live
     * @param refresh     how often at most the document is rebuilt
     * @param maxSeeds    the seed cap per group
     * @param multicast   the multicast group to advertise, or null
     * @param customizers the hooks, in order
     */
    public BeaconDocumentSource(PeerNode node, PeerIdentity identity, InstantSource clock, Duration ttl,
                                Duration refresh, int maxSeeds, String multicast,
                                List<BeaconDocumentCustomizer> customizers) {
        this.node = Objects.requireNonNull(node, "node");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.refresh = Objects.requireNonNull(refresh, "refresh");
        this.maxSeeds = maxSeeds;
        this.multicast = multicast;
        this.customizers = List.copyOf(customizers);
    }

    /** The defaults: a 5-minute TTL, rebuilt at most every 30 s, 8 seeds, no multicast, no hooks. */
    public static BeaconDocumentSource of(PeerNode node, PeerIdentity identity) {
        return new BeaconDocumentSource(node, identity, InstantSource.system(), Duration.ofMinutes(5),
                Duration.ofSeconds(30), 8, null, List.of());
    }

    /** The current document, rebuilt when the refresh interval has passed. */
    @Override
    public BeaconDocument get() {
        long now = clock.millis();
        BeaconDocument current = cached;
        if (current != null && now - builtMillis < refresh.toMillis()) {
            return current;
        }
        synchronized (this) {
            if (cached != null && now - builtMillis < refresh.toMillis()) {
                return cached;
            }
            cached = build();
            builtMillis = now;
            return cached;
        }
    }

    /** Forgets the cached document so the next {@link #get()} rebuilds. */
    public void invalidate() {
        builtMillis = Long.MIN_VALUE;
    }

    /** Builds the document now. */
    public BeaconDocument build() {
        List<BeaconDocument.Group> groups = new ArrayList<>();
        for (GroupRuntime runtime : node.groups()) {
            BeaconDocumentCustomizer.GroupDraft draft = new BeaconDocumentCustomizer.GroupDraft(
                    runtime.advertisement().name() == null ? runtime.id().value() : runtime.advertisement().name(),
                    "aspace://" + runtime.id().value());
            runtime.founding().ifPresent(founding ->
                    draft.founding(Base64.getEncoder().encodeToString(codec.toBytes(founding))));
            for (PeerAdvertisement.Endpoint endpoint : seeds(runtime)) {
                draft.seeds().add(new BeaconDocument.Seed(endpoint.transport(), endpoint.address(), draft.seeds().size()));
            }
            node.wireHints().forEach((k, v) -> {
                if (k.startsWith("wire.")) {
                    try {
                        draft.wire().put(k.substring("wire.".length()), Integer.parseInt(v));
                    } catch (NumberFormatException ignored) {
                        // Not a numeric hint.
                    }
                }
            });
            if (multicast != null && !multicast.isBlank()) {
                draft.multicast(multicast.trim());
            }
            for (BeaconDocumentCustomizer customizer : customizers) {
                customizer.customize(draft, runtime);
            }
            groups.add(draft.toGroup());
        }
        return BeaconDocument.signed(identity, clock.instant(), ttl, groups);
    }

    /**
     * The seeds of one group: this node's advertised endpoints first, then
     * the rendezvous members', then the rest, listening members only, capped.
     */
    List<PeerAdvertisement.Endpoint> seeds(GroupRuntime runtime) {
        List<PeerAdvertisement.Endpoint> out = new ArrayList<>(node.advertisedEndpoints());
        Set<PeerId> listed = new LinkedHashSet<>();
        listed.add(node.peerId());
        GroupMembership membership = runtime.membership();
        for (PeerId rendezvous : membership.withRole(PeerAdvertisement.PeerRole.RENDEZVOUS)) {
            membership.member(rendezvous).ifPresent(member -> addEndpoints(out, listed, member));
        }
        List<GroupMembership.Member> rest = new ArrayList<>(membership.allMembers());
        rest.sort(java.util.Comparator.comparing(m -> m.id().value()));
        for (GroupMembership.Member member : rest) {
            addEndpoints(out, listed, member);
        }
        return out.size() > maxSeeds ? List.copyOf(out.subList(0, maxSeeds)) : out;
    }

    private static void addEndpoints(List<PeerAdvertisement.Endpoint> out, Set<PeerId> listed,
                                     GroupMembership.Member member) {
        if (!listed.add(member.id()) || member.endpoints().isEmpty()) {
            return;
        }
        List<PeerAdvertisement.Endpoint> endpoints = new ArrayList<>(member.endpoints());
        endpoints.sort(java.util.Comparator.comparingInt(PeerAdvertisement.Endpoint::priority));
        out.addAll(endpoints);
    }

    /** The document's time to live. */
    public Duration ttl() {
        return ttl;
    }

    /** The node's groups, for printers. */
    public Map<String, GroupRuntime> groupsByName() {
        Map<String, GroupRuntime> out = new java.util.LinkedHashMap<>();
        for (GroupRuntime runtime : node.groups()) {
            out.put(runtime.advertisement().name() == null ? runtime.id().value() : runtime.advertisement().name(),
                    runtime);
        }
        return out;
    }
}
