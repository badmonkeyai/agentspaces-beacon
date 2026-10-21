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

import ai.badmonkey.agentspaces.bootstrap.BeaconDocument;
import ai.badmonkey.agentspaces.peering.node.GroupRuntime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A hook on the document a beacon serves, applied per group before signing
 * (SRAD DB-C-08): add a parameter, replace the seed list, set the delegation
 * a social beacon publishes. The host collects these as beans.
 */
@FunctionalInterface
public interface BeaconDocumentCustomizer {

    /**
     * Customizes one group's entry.
     *
     * @param draft   the entry as the source built it; mutable
     * @param runtime the group
     */
    void customize(GroupDraft draft, GroupRuntime runtime);

    /** The mutable form of one group's entry. */
    final class GroupDraft {
        private String name;
        private String id;
        private String founding;
        private final List<BeaconDocument.Seed> seeds = new ArrayList<>();
        private final Map<String, Integer> wire = new LinkedHashMap<>();
        private String multicast;
        private final Map<String, String> parameters = new LinkedHashMap<>();
        private String invite;

        public GroupDraft(String name, String id) {
            this.name = Objects.requireNonNull(name, "name");
            this.id = id;
        }

        public String name() {
            return name;
        }

        public GroupDraft name(String value) {
            this.name = Objects.requireNonNull(value, "name");
            return this;
        }

        public String id() {
            return id;
        }

        public String founding() {
            return founding;
        }

        public GroupDraft founding(String base64) {
            this.founding = base64;
            return this;
        }

        public List<BeaconDocument.Seed> seeds() {
            return seeds;
        }

        public Map<String, Integer> wire() {
            return wire;
        }

        public String multicast() {
            return multicast;
        }

        public GroupDraft multicast(String group) {
            this.multicast = group;
            return this;
        }

        public Map<String, String> parameters() {
            return parameters;
        }

        public GroupDraft parameter(String key, String value) {
            parameters.put(key, value);
            return this;
        }

        public String invite() {
            return invite;
        }

        public GroupDraft invite(String delegation) {
            this.invite = delegation;
            return this;
        }

        /** The immutable entry. */
        public BeaconDocument.Group toGroup() {
            return new BeaconDocument.Group(name, id, founding, List.copyOf(seeds),
                    wire.isEmpty() ? null : Map.copyOf(wire), multicast,
                    parameters.isEmpty() ? null : Map.copyOf(parameters), invite);
        }
    }
}
