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

/**
 * Whatever serves the beacon document: the JDK server of
 * {@link BeaconServer}, or a host's own web layer (Spring MVC, Micronaut
 * HTTP) that an application supplies as a bean in its place.
 */
public interface BeaconPublisher extends AutoCloseable {

    /** The document being served. */
    BeaconDocument current();

    /** Where it is served, for the records and the log ({@code http://host:port/.well-known/...}). */
    String url();

    @Override
    void close();
}
