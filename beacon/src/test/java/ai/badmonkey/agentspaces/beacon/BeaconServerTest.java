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

import ai.badmonkey.agentspaces.api.ad.GroupAdvertisement;
import ai.badmonkey.agentspaces.api.ad.PeerAdvertisement;
import ai.badmonkey.agentspaces.api.space.ConflictStrategyType;
import ai.badmonkey.agentspaces.bootstrap.BeaconDocument;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.peering.membership.GroupMembership;
import ai.badmonkey.agentspaces.peering.node.GroupFounding;
import ai.badmonkey.agentspaces.peering.node.GroupRuntime;
import ai.badmonkey.agentspaces.peering.node.PeerNode;
import ai.badmonkey.agentspaces.peering.node.SignedGroupAdvertisement;
import ai.badmonkey.agentspaces.test.SimNetwork;
import ai.badmonkey.agentspaces.test.TestClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The document a beacon builds from its node, and how it is served. */
class BeaconServerTest {

    private final TestClock clock = TestClock.create();
    private final SimNetwork network = new SimNetwork();
    private final List<PeerNode> nodes = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();
    private BeaconServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
        nodes.forEach(PeerNode::close);
    }

    private PeerNode node(PeerIdentity identity, String address, Set<PeerAdvertisement.PeerRole> roles, long seed)
            throws IOException {
        PeerNode node = PeerNode.builder(identity).clock(clock).randomSeed(seed).roles(roles).build();
        node.listen(network.register(address), address, address + ".example:7500");
        nodes.add(node);
        return node;
    }

    private void tickAll(int rounds) {
        for (int i = 0; i < rounds; i++) {
            nodes.forEach(PeerNode::tick);
            clock.advance(Duration.ofSeconds(1));
        }
    }

    private record Fleet(PeerNode beacon, PeerIdentity beaconId, GroupRuntime runtime, PeerNode member) {
    }

    private Fleet fleet() throws IOException {
        PeerIdentity founderId = PeerIdentity.generate();
        SignedGroupAdvertisement founding = GroupFounding.found(founderId, "research-fleet",
                GroupAdvertisement.MembershipPolicy.OPEN, ConflictStrategyType.LEASE_RACE,
                GroupAdvertisement.GossipParameters.defaults(), clock.instant(), Duration.ofDays(1));
        PeerIdentity beaconId = PeerIdentity.generate();
        PeerNode beacon = node(beaconId, "beacon", Set.of(PeerAdvertisement.PeerRole.RENDEZVOUS), 1);
        GroupRuntime runtime = beacon.joinGroup(founding, GroupMembership.Config.defaults(), List.of());
        PeerNode member = node(PeerIdentity.generate(), "member", Set.of(), 2);
        member.joinGroup(founding, GroupMembership.Config.defaults(),
                List.of(new PeerAdvertisement.Endpoint("mem", "beacon", 0)));
        PeerNode dialOnly = PeerNode.builder(PeerIdentity.generate()).clock(clock).randomSeed(3).build();
        dialOnly.transport(network.register("quiet"));
        nodes.add(dialOnly);
        dialOnly.joinGroup(founding, GroupMembership.Config.defaults(),
                List.of(new PeerAdvertisement.Endpoint("mem", "beacon", 0)));
        tickAll(8);
        assertThat(runtime.membership().allMembers()).hasSize(2);
        return new Fleet(beacon, beaconId, runtime, member);
    }

    @Test
    void theDocumentCarriesTheFoundingTheSeedsInOrderAndTheHints() throws Exception {
        Fleet fleet = fleet();
        BeaconDocumentSource source = new BeaconDocumentSource(fleet.beacon, fleet.beaconId, clock,
                Duration.ofSeconds(300), Duration.ofSeconds(30), 8, "239.255.42.99:7787",
                List.of((draft, runtime) -> draft.parameter("region", "eu-west-1")));
        BeaconDocument document = source.get();
        assertThat(document.verifies()).isTrue();
        assertThat(document.signer()).contains(fleet.beaconId.peerId());
        assertThat(document.ttl()).isEqualTo(300);
        assertThat(document.groups()).hasSize(1);
        BeaconDocument.Group group = document.groups().get(0);
        assertThat(group.name()).isEqualTo("research-fleet");
        assertThat(group.id()).isEqualTo("aspace://" + fleet.runtime.id().value());
        assertThat(group.founding()).isNotBlank();
        assertThat(group.seeds()).extracting(BeaconDocument.Seed::address)
                .as("the beacon first, then listening members; the dial-only member is absent")
                .containsExactly("beacon.example:7500", "member.example:7500");
        assertThat(group.wire()).containsKeys("tree", "hashes");
        assertThat(group.multicast()).isEqualTo("239.255.42.99:7787");
        assertThat(group.parameters()).containsEntry("region", "eu-west-1");
        assertThat(source.get()).as("cached within the refresh interval").isSameAs(document);
        clock.advance(Duration.ofMinutes(1));
        assertThat(source.get()).as("rebuilt after it").isNotSameAs(document);
    }

    @Test
    void theServerServesWithEtagCacheAndCorsAndRefusesEverythingElse() throws Exception {
        Fleet fleet = fleet();
        Path copy = Files.createTempDirectory("beacon").resolve("beacon.json");
        server = new BeaconServer(BeaconDocumentSource.of(fleet.beacon, fleet.beaconId), 1000, copy);
        int port = server.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        URI url = URI.create("http://127.0.0.1:" + port + BeaconDocument.WELL_KNOWN);

        HttpResponse<byte[]> ok = http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).startsWith("application/json"));
        assertThat(ok.headers().firstValue("Cache-Control")).contains("public, max-age=300");
        assertThat(ok.headers().firstValue("Access-Control-Allow-Origin")).contains("*");
        String etag = ok.headers().firstValue("ETag").orElseThrow();
        assertThat(BeaconDocument.parse(ok.body()).verifies()).isTrue();
        assertThat(Files.readString(copy)).isEqualTo(new String(ok.body(), java.nio.charset.StandardCharsets.UTF_8));

        HttpResponse<Void> notModified = http.send(HttpRequest.newBuilder(url).header("If-None-Match", etag).GET().build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(notModified.statusCode()).isEqualTo(304);
        HttpResponse<Void> head = http.send(HttpRequest.newBuilder(url).method("HEAD", HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.discarding());
        assertThat(head.statusCode()).isEqualTo(200);
        HttpResponse<Void> post = http.send(HttpRequest.newBuilder(url).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(post.statusCode()).isEqualTo(405);
        HttpResponse<Void> elsewhere = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/x"))
                .GET().build(), HttpResponse.BodyHandlers.discarding());
        assertThat(elsewhere.statusCode()).isEqualTo(404);
        assertThat(server.url()).endsWith(BeaconDocument.WELL_KNOWN);
    }

    @Test
    void aSourceThatAsksTooFastIsToldToSlowDown() throws Exception {
        Fleet fleet = fleet();
        server = new BeaconServer(BeaconDocumentSource.of(fleet.beacon, fleet.beaconId), 2, null);
        int port = server.start(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        URI url = URI.create("http://127.0.0.1:" + port + BeaconDocument.WELL_KNOWN);
        int tooMany = 0;
        for (int i = 0; i < 12; i++) {
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(url).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() == 429) {
                tooMany++;
                assertThat(response.headers().firstValue("Retry-After")).contains("1");
            }
        }
        assertThat(tooMany).as("a burst of four, then refusals").isGreaterThanOrEqualTo(6);
    }

    @Test
    void theRecordsAndTheMainOptions() throws Exception {
        Fleet fleet = fleet();
        BeaconDocument document = BeaconDocumentSource.of(fleet.beacon, fleet.beaconId).get();
        String zone = RecordPrinter.zone(document, "Corp.Example.",
                "https://agentspaces.corp.example/.well-known/agentspaces/beacon.json");
        assertThat(zone).contains("_agentspaces._tcp.corp.example.")
                .contains("IN PTR  research-fleet._agentspaces._tcp.corp.example.")
                .contains("IN SRV  10 50 7500 beacon.example.")
                .contains("IN SRV  20 50 7500 member.example.")
                .contains("\"group=research-fleet\"").contains("\"id=aspace://" + fleet.runtime.id().value() + "\"")
                .contains("\"scheme=mem\"")
                .contains("agentspaces.corp.example.")
                .contains("\"url=https://agentspaces.corp.example/.well-known/agentspaces/beacon.json\"");
        assertThat(RecordPrinter.label("Weekend Hack!")).isEqualTo("weekend-hack");

        Map<String, String> options = BeaconMain.parse(new String[]{"--founding", "research-fleet-v1", "--no-tls",
                "--roles=rendezvous,relay", "--seeds", "a:1,b:2", "--advertise", "beacon.corp.example:7500"});
        var properties = BeaconMain.properties(options);
        assertThat(properties.getRoles()).containsExactly("rendezvous", "relay");
        assertThat(properties.getTransport().getTls().getEnabled()).isFalse();
        assertThat(properties.getAdvertise()).isEqualTo("beacon.corp.example:7500");
        assertThat(properties.getGroups().get(0).getFounding()).isEqualTo("research-fleet-v1");
        assertThat(properties.getGroups().get(0).getSeeds()).containsExactly("a:1", "b:2");
        assertThat(BeaconMain.hostPort("0.0.0.0:7580")).containsExactly("0.0.0.0", "7580");
    }
}
