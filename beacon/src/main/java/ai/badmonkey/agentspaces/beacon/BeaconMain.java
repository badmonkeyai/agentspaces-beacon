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

import ai.badmonkey.agentspaces.agent.AgentSpaces;
import ai.badmonkey.agentspaces.bootstrap.BeaconDocument;
import ai.badmonkey.agentspaces.identity.PeerIdentity;
import ai.badmonkey.agentspaces.peering.node.PeerNode;
import ai.badmonkey.agentspaces.spring.AgentSpacesAutoConfiguration;
import ai.badmonkey.agentspaces.spring.AgentSpacesProperties;
import ai.badmonkey.agentspaces.spring.Authorizers;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The standalone beacon (SRAD DB-B-07): founds or joins a group from the
 * starter's own properties (so TLS, keystores, roles, and join-by-id all
 * work as in any peer), serves the document, and prints the records.
 *
 * <pre>
 * java -jar agentspaces-beacon.jar --keystore ./beacon-keys --founding research-fleet-v1 \
 *      --bind 0.0.0.0:7500 --advertise beacon.corp.example:7500 --http 0.0.0.0:7580 \
 *      --roles rendezvous --domain corp.example --url https://agentspaces.corp.example/.well-known/agentspaces/beacon.json
 * </pre>
 *
 * Options: {@code --keystore}, {@code --founding <string>} or {@code --join <aspace://id>},
 * {@code --name <group name>}, {@code --seeds a:1,b:2}, {@code --bind}, {@code --advertise},
 * {@code --http host:port} (default {@code 0.0.0.0:7580}), {@code --roles rendezvous,relay},
 * {@code --no-tls}, {@code --membership open|invite}, {@code --domain} (the zone to print records for),
 * {@code --url} (the public document URL to print), {@code --write-to <file>}, {@code --ttl <seconds>},
 * {@code --refresh <seconds>}, {@code --max-seeds <n>}, {@code --rate <per-second>},
 * {@code --multicast host:port}.
 */
public final class BeaconMain {

    private BeaconMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        if (options.containsKey("help")) {
            System.out.println("options: --keystore --founding|--join --name --seeds --bind --advertise --http"
                    + " --roles --no-tls --membership --domain --url --write-to --ttl --refresh --max-seeds --rate"
                    + " --multicast");
            return;
        }
        AgentSpacesProperties properties = properties(options);
        AgentSpacesAutoConfiguration wiring = new AgentSpacesAutoConfiguration();
        PeerIdentity identity = wiring.agentSpacesIdentity(properties);
        PeerNode node = wiring.agentSpacesNode(properties, identity);
        Authorizers authorizers = wiring.agentSpacesAuthorizers(properties, identity);
        AgentSpaces spaces = wiring.agentSpaces(properties, node, identity, authorizers);
        // The plain-Java start, as the runtime does it: no Spring lifecycle, the node ticks on its own clock.
        node.startTicking(Duration.ofMillis(properties.getTickMillis()));

        Duration ttl = Duration.ofSeconds(Long.parseLong(options.getOrDefault("ttl", "300")));
        Duration refresh = Duration.ofSeconds(Long.parseLong(options.getOrDefault("refresh", "30")));
        int maxSeeds = Integer.parseInt(options.getOrDefault("max-seeds", "8"));
        double rate = Double.parseDouble(options.getOrDefault("rate", "50"));
        BeaconDocumentSource source = new BeaconDocumentSource(node, identity, java.time.InstantSource.system(), ttl,
                refresh, maxSeeds, options.get("multicast"), List.of());
        Path writeTo = options.containsKey("write-to") ? Path.of(options.get("write-to")) : null;
        BeaconServer server = new BeaconServer(source, rate, writeTo);
        String[] http = hostPort(options.getOrDefault("http", "0.0.0.0:7580"));
        int port = server.start(new InetSocketAddress(http[0], Integer.parseInt(http[1])));
        String url = options.getOrDefault("url", server.url());

        BeaconDocument document = server.current();
        System.out.println("beacon: peer " + identity.peerId().display() + " serving " + document.groups().size()
                + " group(s) at " + server.url() + (port == 0 ? "" : ""));
        for (BeaconDocument.Group group : document.groups()) {
            System.out.println("beacon: " + group.name() + " " + group.id() + " seeds " + group.seeds().size());
        }
        if (options.containsKey("domain")) {
            System.out.println("register in " + options.get("domain") + ":");
            System.out.print(RecordPrinter.zone(document, options.get("domain"), url));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            spaces.close();
            node.close();
        }, "beacon-shutdown"));
        Thread.currentThread().join();
    }

    /** The starter properties from the options; package-private for tests. */
    static AgentSpacesProperties properties(Map<String, String> options) {
        AgentSpacesProperties properties = new AgentSpacesProperties();
        if (options.containsKey("keystore")) {
            properties.setKeystore(options.get("keystore"));
        }
        properties.setBind(options.getOrDefault("bind", "0.0.0.0:7500"));
        if (options.containsKey("advertise")) {
            properties.setAdvertise(options.get("advertise"));
        }
        List<String> roles = new ArrayList<>();
        for (String role : options.getOrDefault("roles", "rendezvous").split(",")) {
            if (!role.isBlank()) {
                roles.add(role.trim());
            }
        }
        properties.setRoles(roles);
        if (options.containsKey("no-tls")) {
            properties.getTransport().getTls().setEnabled(false);
        }
        AgentSpacesProperties.Group group = new AgentSpacesProperties.Group();
        group.setName(options.getOrDefault("name", options.getOrDefault("founding", "fleet")));
        if (options.containsKey("join")) {
            group.setJoin(options.get("join"));
        } else {
            group.setFounding(options.getOrDefault("founding", group.getName()));
        }
        group.setMembership(options.getOrDefault("membership", "open"));
        List<String> seeds = new ArrayList<>();
        for (String seed : options.getOrDefault("seeds", "").split(",")) {
            if (!seed.isBlank()) {
                seeds.add(seed.trim());
            }
        }
        group.setSeeds(seeds);
        properties.setGroups(List.of(group));
        if (options.containsKey("multicast")) {
            properties.getMulticast().setEnabled(true);
            properties.getMulticast().setGroup(options.get("multicast"));
        }
        return properties;
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                throw new IllegalArgumentException("unexpected argument " + arg);
            }
            String key = arg.substring(2);
            int eq = key.indexOf('=');
            if (eq >= 0) {
                out.put(key.substring(0, eq), key.substring(eq + 1));
            } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                out.put(key, args[++i]);
            } else {
                out.put(key, "true");
            }
        }
        return out;
    }

    static String[] hostPort(String value) {
        int colon = value.lastIndexOf(':');
        return colon < 0 ? new String[]{value, "7580"} : new String[]{value.substring(0, colon), value.substring(colon + 1)};
    }
}
