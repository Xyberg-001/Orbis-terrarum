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

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** host -> [ip, expiry millis] */
    private static final ConcurrentHashMap<String, Object[]> DOH_CACHE = new ConcurrentHashMap<>();
    private static volatile boolean announced;

    private OrbisHttp() {}

    public static Response get(String url, Map<String, String> headers, int timeoutSeconds) throws IOException, InterruptedException {
        return send("GET", url, headers, null, null, timeoutSeconds);
    }

    public static Response post(String url, Map<String, String> headers, String contentType, byte[] body, int timeoutSeconds)
            throws IOException, InterruptedException {
        return send("POST", url, headers, contentType, body, timeoutSeconds);
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
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
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
