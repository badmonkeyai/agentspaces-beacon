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
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Serves the beacon document over HTTP on the JDK server (SRAD DB-B-01,
 * DB-B-06, DB-Q-SEC-05): {@code GET} and {@code HEAD} of
 * {@link BeaconDocument#WELL_KNOWN} only, {@code Cache-Control} from the
 * document's TTL, an {@code ETag} with {@code 304} on a match, read-only
 * CORS so a browser tool may read it, and a per-source token bucket. An
 * optional file copy is written on every rebuild for an ingress to serve.
 */
public final class BeaconServer implements BeaconPublisher {

    private static final System.Logger LOG = System.getLogger(BeaconServer.class.getName());

    private final Supplier<BeaconDocument> source;
    private final double ratePerSecond;
    private final Path writeTo;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private HttpServer server;
    private volatile String lastWritten;

    /**
     * A server.
     *
     * @param source        the document
     * @param ratePerSecond requests per second per source address (burst of twice that)
     * @param writeTo       a file to keep the document in, or null
     */
    public BeaconServer(Supplier<BeaconDocument> source, double ratePerSecond, Path writeTo) {
        this.source = Objects.requireNonNull(source, "source");
        this.ratePerSecond = ratePerSecond;
        this.writeTo = writeTo;
    }

    /**
     * Starts serving.
     *
     * @param address the address to bind
     * @return the bound port
     * @throws IOException when the address cannot be bound
     */
    public synchronized int start(InetSocketAddress address) throws IOException {
        if (server != null) {
            throw new IllegalStateException("already started");
        }
        server = HttpServer.create(address, 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::route);
        server.start();
        return server.getAddress().getPort();
    }

    /** The bound port. */
    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("not started");
        }
        return server.getAddress().getPort();
    }

    @Override
    public BeaconDocument current() {
        BeaconDocument document = source.get();
        write(document);
        return document;
    }

    @Override
    public synchronized String url() {
        if (server == null) {
            return BeaconDocument.WELL_KNOWN;
        }
        InetSocketAddress address = server.getAddress();
        String host = address.getAddress().isAnyLocalAddress() ? "localhost" : address.getHostString();
        return "http://" + host + ":" + address.getPort() + BeaconDocument.WELL_KNOWN;
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if (!path.equals(BeaconDocument.WELL_KNOWN)) {
                send(exchange, 404, "not found\n".getBytes(StandardCharsets.UTF_8), "text/plain", false);
                return;
            }
            if (!method.equals("GET") && !method.equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                send(exchange, 405, new byte[0], "text/plain", false);
                return;
            }
            String source = exchange.getRemoteAddress() == null ? "?" : exchange.getRemoteAddress().getAddress().getHostAddress();
            if (!buckets.computeIfAbsent(source, s -> new Bucket(ratePerSecond)).tryAcquire()) {
                exchange.getResponseHeaders().set("Retry-After", "1");
                send(exchange, 429, "slow down\n".getBytes(StandardCharsets.UTF_8), "text/plain", false);
                return;
            }
            BeaconDocument document = current();
            byte[] body = document.toBytes();
            String etag = "\"" + etag(body) + "\"";
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=" + Math.max(0, document.ttl()));
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, HEAD");
            String ifNoneMatch = exchange.getRequestHeaders().getFirst("If-None-Match");
            if (ifNoneMatch != null && ifNoneMatch.trim().equals(etag)) {
                exchange.sendResponseHeaders(304, -1);
                exchange.close();
                return;
            }
            send(exchange, 200, body, "application/json; charset=utf-8", method.equals("HEAD"));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "beacon request failed", e);
            send(exchange, 500, new byte[0], "text/plain", false);
        }
    }

    private static void send(HttpExchange exchange, int status, byte[] body, String type, boolean headOnly)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        if (headOnly) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        } else {
            exchange.close();
        }
    }

    private void write(BeaconDocument document) {
        if (writeTo == null) {
            return;
        }
        String json = document.toJson();
        if (json.equals(lastWritten)) {
            return;
        }
        try {
            Path temp = Files.createTempFile(writeTo.toAbsolutePath().getParent(), "beacon", ".tmp");
            Files.writeString(temp, json, StandardCharsets.UTF_8);
            Files.move(temp, writeTo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            lastWritten = json;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "cannot write the beacon document to " + writeTo + ": " + e);
        }
    }

    /** The ETag of a body: the first 16 bytes of its SHA-256, hex. */
    static String etag(byte[] body) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token bucket: {@code rate} per second, a burst of twice the rate. */
    static final class Bucket {
        private final double rate;
        private final double capacity;
        private double tokens;
        private long lastNanos = System.nanoTime();

        Bucket(double rate) {
            this.rate = rate;
            this.capacity = Math.max(1.0, rate * 2);
            this.tokens = capacity;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastNanos) / 1_000_000_000.0 * rate);
            lastNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
