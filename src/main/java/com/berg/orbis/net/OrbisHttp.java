package com.berg.orbis.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP for every download the mod makes, with one job beyond the JDK client:
 * keep working when the operating system's DNS resolver cannot look a host
 * up. Browsers survive broken ISP resolvers because they do their own
 * encrypted DNS; Java programs ask Windows and fail. So when a request dies
 * on name resolution, the host is resolved over DNS-over-HTTPS (Google, then
 * Cloudflare, both reached by IP address) and the request is repeated over a
 * TLS socket opened to that IP, with the real host name used for SNI and
 * certificate checking, so security is unchanged.
 */
public final class OrbisHttp {

    public record Response(int status, Map<String, String> headers, byte[] body) {
        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /**
     * Public root certificates Java's own list lacks while browsers, Windows and Mozilla trust them (resources
     * orbis-roots, from Mozilla's bundle): HARICA's 2021 roots, which GÉANT's certificates for Europe's universities
     * and public bodies chain to since 2025 (Finland's environment institute, Spain's IDEE), and Deutsche Telekom's
     * newer roots (Berlin's geoportal). Certificates are still checked in full, host name included: a server is
     * trusted when Java's list or one of these roots vouches for it.
     */
    private static final String[] EXTRA_ROOTS = {"HARICA_TLS_RSA_Root_CA_2021.pem", "HARICA_TLS_ECC_Root_CA_2021.pem",
            "Telekom_Security_TLS_RSA_Root_2023.pem", "Telekom_Security_TLS_ECC_Root_2020.pem"};
    private static final javax.net.ssl.SSLContext TLS = tls();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .sslContext(TLS)
            .build();

    private static javax.net.ssl.SSLContext tls() {
        try {
            javax.net.ssl.X509ExtendedTrustManager builtIn = trustManager(null);
            java.security.KeyStore extra = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
            extra.load(null, null);
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            for (String name : EXTRA_ROOTS) {
                try (InputStream in = OrbisHttp.class.getResourceAsStream("/orbis-roots/" + name)) {
                    if (in != null) extra.setCertificateEntry(name, cf.generateCertificate(in));
                }
            }
            javax.net.ssl.X509ExtendedTrustManager added = trustManager(extra);
            javax.net.ssl.SSLContext ctx = javax.net.ssl.SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{new EitherTrustManager(builtIn, added)}, null);
            return ctx;
        } catch (Exception e) {
            System.err.println("[orbis] Extra root certificates not loaded (" + e + "); Java's own list only");
            try {
                return javax.net.ssl.SSLContext.getDefault();
            } catch (java.security.NoSuchAlgorithmException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    private static javax.net.ssl.X509ExtendedTrustManager trustManager(java.security.KeyStore store) throws Exception {
        javax.net.ssl.TrustManagerFactory f = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        f.init(store);
        for (javax.net.ssl.TrustManager tm : f.getTrustManagers()) {
            if (tm instanceof javax.net.ssl.X509ExtendedTrustManager x) return x;
        }
        throw new IllegalStateException("no X509 trust manager");
    }

    /** Trusts a server when either list does (each checks the whole chain and, for HTTPS, the host name). */
    private static final class EitherTrustManager extends javax.net.ssl.X509ExtendedTrustManager {
        private final javax.net.ssl.X509ExtendedTrustManager a, b;

        EitherTrustManager(javax.net.ssl.X509ExtendedTrustManager a, javax.net.ssl.X509ExtendedTrustManager b) {
            this.a = a;
            this.b = b;
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType, Socket socket) throws java.security.cert.CertificateException {
            try {
                a.checkServerTrusted(chain, authType, socket);
            } catch (java.security.cert.CertificateException e) {
                b.checkServerTrusted(chain, authType, socket);
            }
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine)
                throws java.security.cert.CertificateException {
            try {
                a.checkServerTrusted(chain, authType, engine);
            } catch (java.security.cert.CertificateException e) {
                b.checkServerTrusted(chain, authType, engine);
            }
        }

        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            try {
                a.checkServerTrusted(chain, authType);
            } catch (java.security.cert.CertificateException e) {
                b.checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType, Socket socket) throws java.security.cert.CertificateException {
            a.checkClientTrusted(chain, authType, socket);
        }

        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType, javax.net.ssl.SSLEngine engine)
                throws java.security.cert.CertificateException {
            a.checkClientTrusted(chain, authType, engine);
        }

        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            a.checkClientTrusted(chain, authType);
        }

        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
            java.security.cert.X509Certificate[] x = a.getAcceptedIssuers(), y = b.getAcceptedIssuers();
            java.security.cert.X509Certificate[] out = java.util.Arrays.copyOf(x, x.length + y.length);
            System.arraycopy(y, 0, out, x.length, y.length);
            return out;
        }
    }

    /** host -> [ip, expiry millis] */
    private static final ConcurrentHashMap<String, Object[]> DOH_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean announced;

    private OrbisHttp() {}

    /** Requests running now, by host: what pre-generation is waiting for, shown to the players (PregenTask's bar). */
    private static final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> ACTIVE = new ConcurrentHashMap<>();

    /** The hosts with requests running now and how many each. */
    public static Map<String, Integer> activeRequests() {
        Map<String, Integer> out = new java.util.TreeMap<>();
        ACTIVE.forEach((h, n) -> {
            int v = n.get();
            if (v > 0) out.put(h, v);
        });
        return out;
    }

    private static java.util.concurrent.atomic.AtomicInteger begin(String url) {
        String host;
        try {
            host = URI.create(url).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        java.util.concurrent.atomic.AtomicInteger n = ACTIVE.computeIfAbsent(host == null ? "?" : host, h -> new java.util.concurrent.atomic.AtomicInteger());
        n.incrementAndGet();
        return n;
    }

    /** Bytes of response bodies received since the game started (all hosts), for speed and data-use figures. */
    private static final java.util.concurrent.atomic.AtomicLong RECEIVED = new java.util.concurrent.atomic.AtomicLong();

    public static long bytesReceived() {
        return RECEIVED.get();
    }

    public static Response get(String url, Map<String, String> headers, int timeoutSeconds) throws IOException, InterruptedException {
        java.util.concurrent.atomic.AtomicInteger n = begin(url);
        try {
            Response r = send("GET", url, headers, null, null, timeoutSeconds);
            if (r != null && r.body() != null) RECEIVED.addAndGet(r.body().length);
            return r;
        } finally {
            n.decrementAndGet();
        }
    }

    public static Response post(String url, Map<String, String> headers, String contentType, byte[] body, int timeoutSeconds)
            throws IOException, InterruptedException {
        java.util.concurrent.atomic.AtomicInteger n = begin(url);
        try {
            return send("POST", url, headers, contentType, body, timeoutSeconds);
        } finally {
            n.decrementAndGet();
        }
    }

    private static Response send(String method, String url, Map<String, String> headers, String contentType, byte[] body, int timeoutSeconds)
            throws IOException, InterruptedException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds));
            if (headers != null) headers.forEach(b::header);
            if ("POST".equals(method)) {
                b.header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                b.GET();
            }
            // The request timeout only covers the wait for the response headers; a body that stops arriving
            // (a connection an ISP box silently dropped) would block forever, so the whole exchange gets a deadline.
            java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> future = CLIENT.sendAsync(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            HttpResponse<byte[]> resp;
            try {
                resp = future.get(timeoutSeconds + 15L, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                future.cancel(true);
                throw new java.net.http.HttpTimeoutException("no complete response within " + (timeoutSeconds + 15) + " s from " + url);
            } catch (java.util.concurrent.ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof IOException io) throw io;
                if (c instanceof RuntimeException re) throw re;
                throw new IOException(c);
            }
            Map<String, String> h = new HashMap<>();
            resp.headers().map().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), String.join(", ", v)));
            return new Response(resp.statusCode(), h, resp.body());
        } catch (IOException e) {
            if (!isNameResolutionFailure(e)) throw e;
            String host = URI.create(url).getHost();
            String ip = resolveOverHttps(host);
            if (ip == null) throw new UnknownHostException(host + " (OS resolver failed and DNS-over-HTTPS could not resolve it either)");
            if (!announced) {
                announced = true;
                System.out.println("[orbis] The system DNS resolver cannot resolve " + host + "; using DNS-over-HTTPS for such hosts from now on");
            }
            return rawRequest(method, url, ip, headers, contentType, body, timeoutSeconds, 0);
        }
    }

    private static boolean isNameResolutionFailure(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof UnknownHostException || c instanceof UnresolvedAddressException) return true;
            if (c instanceof ConnectException && c.getMessage() == null && c.getCause() == null) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ large files

    /** Bytes received so far and the file's size (-1 while unknown), during a {@link #download}. */
    public interface DownloadProgress {
        void update(long done, long total);
    }

    /** The size of the file at a URL (the Content-Length of a HEAD request), or -1 when the server does not say. */
    public static long size(String url, Map<String, String> headers) throws IOException, InterruptedException {
        for (int hop = 0; hop < 6; hop++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody());
            if (headers != null) headers.forEach(b::header);
            HttpResponse<Void> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.discarding());
            String next = redirect(r);
            if (next != null) {
                url = next;
                continue;
            }
            if (r.statusCode() != 200) return -1;
            return r.headers().firstValueAsLong("content-length").orElse(-1);
        }
        return -1;
    }

    /**
     * Where a redirect points, as https, or null when the answer is not one. Geofabrik sends its "-latest" files on
     * to the dated file, sometimes as plain http, which Java's client will not follow from an https page.
     */
    private static String redirect(HttpResponse<?> r) {
        int s = r.statusCode();
        if (s != 301 && s != 302 && s != 303 && s != 307 && s != 308) return null;
        String loc = r.headers().firstValue("location").orElse(null);
        if (loc == null) return null;
        String next = r.uri().resolve(loc).toString();
        return next.startsWith("http://") ? "https://" + next.substring(7) : next;
    }

    /**
     * Streams a large file (a country's map data, gigabytes) to {@code target} without holding it in memory. It is
     * written to {@code <target>.part} first and moved into place when complete; a part left by an earlier attempt
     * is continued where it stopped (a Range request) when the server allows it. A connection that delivers nothing
     * for 60 s is dropped (the part stays for next time); {@code cancelled} stops it between reads.
     */
    public static void download(String url, java.nio.file.Path target, Map<String, String> headers, DownloadProgress progress,
                                java.util.function.BooleanSupplier cancelled) throws IOException, InterruptedException {
        java.util.concurrent.atomic.AtomicInteger n = begin(url);
        try {
            downloadInner(url, target, headers, progress, cancelled);
        } finally {
            n.decrementAndGet();
        }
    }

    private static void downloadInner(String url, java.nio.file.Path target, Map<String, String> headers, DownloadProgress progress,
                                java.util.function.BooleanSupplier cancelled) throws IOException, InterruptedException {
        java.nio.file.Path part = target.resolveSibling(target.getFileName() + ".part");
        java.nio.file.Files.createDirectories(target.toAbsolutePath().getParent());
        long have = java.nio.file.Files.exists(part) ? java.nio.file.Files.size(part) : 0;
        HttpResponse<InputStream> r = null;
        for (int hop = 0; hop < 6; hop++) {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET();
            if (headers != null) headers.forEach(b::header);
            if (have > 0) b.header("Range", "bytes=" + have + "-");
            r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
            String next = redirect(r);
            if (next == null) break;
            r.body().close();
            url = next;
        }
        int status = r.statusCode();
        if (status != 200 && status != 206) {
            r.body().close();
            throw new IOException("HTTP " + status + " for " + url);
        }
        boolean append = status == 206 && have > 0;
        long length = r.headers().firstValueAsLong("content-length").orElse(-1);
        long total = length < 0 ? -1 : append ? have + length : length;
        long[] done = {append ? have : 0};
        long[] lastByte = {System.currentTimeMillis()};
        InputStream in = r.body();
        // A dropped connection can leave a read waiting for ever: close the stream when nothing arrives for 60 s.
        Thread watchdog = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(5_000);
                    if (System.currentTimeMillis() - lastByte[0] > 60_000) {
                        in.close();
                        return;
                    }
                }
            } catch (InterruptedException | IOException ignored) {
            }
        }, "Orbis-download-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try (InputStream src = in; OutputStream out = java.nio.file.Files.newOutputStream(part, append
                ? new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND}
                : new java.nio.file.OpenOption[]{java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                java.nio.file.StandardOpenOption.WRITE})) {
            byte[] buf = new byte[1 << 16];
            long lastReport = 0;
            int n;
            while ((n = src.read(buf)) > 0) {
                if (cancelled != null && cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("download stopped");
                out.write(buf, 0, n);
                done[0] += n;
                long now = System.currentTimeMillis();
                lastByte[0] = now;
                if (now - lastReport > 500) {
                    lastReport = now;
                    progress.update(done[0], total);
                }
            }
        } finally {
            watchdog.interrupt();
        }
        progress.update(done[0], total);
        if (total >= 0 && done[0] != total) {
            throw new IOException(String.format(Locale.ROOT, "download stopped at %,d of %,d bytes (it continues next time)", done[0], total));
        }
        java.nio.file.Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    // ------------------------------------------------------------------ DNS over HTTPS

    /** Resolves a host over DNS-over-HTTPS; cached for the record's TTL (at least 60 s). Null when nothing answers. */
    public static String resolveOverHttps(String host) {
        Object[] cached = DOH_CACHE.get(host);
        if (cached != null && System.currentTimeMillis() < (long) cached[1]) return (String) cached[0];
        String[] endpoints = {
                "https://8.8.8.8/resolve?type=A&name=" + URLEncoder.encode(host, StandardCharsets.UTF_8),
                "https://1.1.1.1/dns-query?type=A&name=" + URLEncoder.encode(host, StandardCharsets.UTF_8)
        };
        for (String ep : endpoints) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(ep)).timeout(Duration.ofSeconds(8))
                        .header("Accept", "application/dns-json").GET().build();
                HttpResponse<String> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) continue;
                JsonObject root = JsonParser.parseString(resp.body()).getAsJsonObject();
                JsonArray answers = root.getAsJsonArray("Answer");
                if (answers == null) continue;
                for (JsonElement el : answers) {
                    JsonObject a = el.getAsJsonObject();
                    if (a.get("type").getAsInt() == 1) {
                        String ip = a.get("data").getAsString();
                        long ttl = Math.max(60, a.has("TTL") ? a.get("TTL").getAsLong() : 300) * 1000L;
                        DOH_CACHE.put(host, new Object[]{ip, System.currentTimeMillis() + ttl});
                        return ip;
                    }
                }
            } catch (Exception ignored) {
                // try the next resolver
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ raw HTTP/1.1 over a socket to a known IP

    private static Response rawRequest(String method, String url, String ip, Map<String, String> headers, String contentType,
                                       byte[] body, int timeoutSeconds, int redirects) throws IOException {
        URI uri = URI.create(url);
        String host = uri.getHost();
        boolean tls = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
        String path = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : "");

        Socket plain = new Socket();
        plain.connect(new InetSocketAddress(ip, port), 10_000);
        plain.setSoTimeout(timeoutSeconds * 1000);
        final Socket socket;
        if (tls) {
            SSLSocketFactory factory = TLS.getSocketFactory();
            SSLSocket ssl = (SSLSocket) factory.createSocket(plain, host, port, true);
            SSLParameters params = ssl.getSSLParameters();
            params.setServerNames(List.of(new SNIHostName(host)));
            params.setEndpointIdentificationAlgorithm("HTTPS"); // certificate must match the real host name
            ssl.setSSLParameters(params);
            ssl.startHandshake();
            socket = ssl;
        } else {
            socket = plain;
        }
        try (socket) {
            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append("\r\n");
            req.append("Connection: close\r\n");
            req.append("Accept-Encoding: identity\r\n");
            if (headers != null) headers.forEach((k, v) -> req.append(k).append(": ").append(v).append("\r\n"));
            if (body != null) {
                req.append("Content-Type: ").append(contentType).append("\r\n");
                req.append("Content-Length: ").append(body.length).append("\r\n");
            }
            req.append("\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (body != null) out.write(body);
            out.flush();

            InputStream in = new BufferedInputStream(socket.getInputStream());
            String statusLine = readLine(in);
            if (statusLine == null || !statusLine.startsWith("HTTP/")) throw new IOException("bad HTTP response from " + host);
            String[] parts = statusLine.split(" ", 3);
            int status = Integer.parseInt(parts[1]);
            Map<String, String> h = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) h.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
            }
            byte[] data;
            if ("chunked".equalsIgnoreCase(h.getOrDefault("transfer-encoding", ""))) {
                data = readChunked(in);
            } else if (h.containsKey("content-length")) {
                data = in.readNBytes(Integer.parseInt(h.get("content-length")));
            } else {
                data = in.readAllBytes();
            }
            if ((status == 301 || status == 302 || status == 307 || status == 308) && h.containsKey("location") && redirects < 3) {
                String loc = h.get("location");
                if (loc.startsWith("/")) loc = uri.getScheme() + "://" + host + loc;
                String newHost = URI.create(loc).getHost();
                String newIp = newHost.equals(host) ? ip : resolveOverHttps(newHost);
                if (newIp == null) throw new UnknownHostException(newHost);
                String m = (status == 307 || status == 308) ? method : "GET";
                return rawRequest(m, loc, newIp, headers, contentType, m.equals("GET") ? null : body, timeoutSeconds, redirects + 1);
            }
            return new Response(status, h, data);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) break;
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
            sizeLine = sizeLine.trim();
            if (sizeLine.isEmpty()) continue;
            int size = Integer.parseInt(sizeLine, 16);
            if (size == 0) {
                // trailers until blank line
                String t;
                while ((t = readLine(in)) != null && !t.isEmpty()) { /* skip */ }
                break;
            }
            out.write(in.readNBytes(size));
            readLine(in); // CRLF after the chunk
        }
        return out.toByteArray();
    }
}
