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
import ai.badmonkey.agentspaces.bootstrap.DnsSdLocator;
import ai.badmonkey.agentspaces.bootstrap.TxtLocator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The DNS records an operator registers for a beacon (SRAD DB-B-04, appendix
 * B): per group, the DNS-SD PTR, SRV, and TXT set under a domain, and the
 * WPAD-style TXT pointer at {@code agentspaces.<domain>}; the SRV targets are
 * the document's seeds. Rendered as a zone fragment the operator pastes, or
 * hands to the DNS team.
 */
public final class RecordPrinter {

    private RecordPrinter() {
    }

    /**
     * The zone fragment for every group in the document.
     *
     * @param document the document
     * @param domain   the domain to register under ({@code corp.example})
     * @param url      the document's public URL, or null when it has none
     * @return the fragment
     */
    public static String zone(BeaconDocument document, String domain, String url) {
        String zone = domain.trim().toLowerCase(Locale.ROOT);
        if (zone.endsWith(".")) {
            zone = zone.substring(0, zone.length() - 1);
        }
        StringBuilder out = new StringBuilder();
        out.append("; AgentSpaces beacon records for ").append(zone).append(" (SRAD appendix B)\n");
        String service = DnsSdLocator.SERVICE + "." + zone + ".";
        for (BeaconDocument.Group group : document.groups()) {
            String label = label(group.name());
            String instance = label + "." + service;
            out.append(pad(service)).append("IN PTR  ").append(instance).append('\n');
            List<BeaconDocument.Seed> seeds = new ArrayList<>(group.seeds());
            seeds.sort(java.util.Comparator.comparingInt(BeaconDocument.Seed::priority));
            String scheme = null;
            for (BeaconDocument.Seed seed : seeds) {
                String[] hostPort = split(seed.address());
                if (hostPort[1].isEmpty()) {
                    continue;
                }
                if (scheme == null) {
                    scheme = seed.transport();
                }
                out.append(pad(instance)).append("IN SRV  ").append(10 + seed.priority() * 10).append(" 50 ")
                        .append(hostPort[1]).append(' ').append(fqdn(hostPort[0], zone)).append('\n');
            }
            StringBuilder txt = new StringBuilder("\"v=1\" \"group=").append(group.name()).append('"');
            if (group.id() != null) {
                txt.append(" \"id=").append(group.id()).append('"');
            }
            if (scheme != null) {
                txt.append(" \"scheme=").append(scheme).append('"');
            }
            if (url != null) {
                txt.append(" \"url=").append(url).append('"');
            }
            if (group.multicast() != null) {
                txt.append(" \"multicast=").append(group.multicast()).append('"');
            }
            out.append(pad(instance)).append("IN TXT  ").append(txt).append('\n');
        }
        if (url != null) {
            out.append(pad(TxtLocator.LABEL + "." + zone + ".")).append("IN TXT  \"v=1\" \"url=").append(url)
                    .append("\"\n");
        }
        return out.toString();
    }

    /** A DNS-SD instance label from a group name: lower-case, spaces to dashes, nothing but letters, digits, dashes. */
    static String label(String name) {
        StringBuilder out = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c)) {
                out.append(c);
            } else if (c == ' ' || c == '-' || c == '_' || c == '.') {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                    out.append('-');
                }
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        return out.isEmpty() ? "fleet" : out.toString();
    }

    private static String fqdn(String host, String zone) {
        if (host.indexOf(':') >= 0 || host.matches("[0-9.]+")) {
            return host; // a literal: the operator substitutes a name
        }
        return host.endsWith(".") ? host : host.contains(".") ? host + "." : host + "." + zone + ".";
    }

    private static String[] split(String address) {
        String a = address.trim();
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            String rest = close < 0 ? "" : a.substring(close + 1);
            return new String[]{close < 0 ? a : a.substring(1, close), rest.startsWith(":") ? rest.substring(1) : ""};
        }
        int colon = a.lastIndexOf(':');
        return colon < 0 ? new String[]{a, ""} : new String[]{a.substring(0, colon), a.substring(colon + 1)};
    }

    private static String pad(String owner) {
        StringBuilder out = new StringBuilder(owner);
        while (out.length() < 56) {
            out.append(' ');
        }
        return out.append(' ').toString();
    }
}
