package org.example.core;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

public class WebServer {
    private static HttpServer server;
    private static int port;
    private static final Gson gson = new Gson();

    public static synchronized void start(int p) {
        if (server != null) {
            return;
        }
        Database.init();
        port = p;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            // static assets and index
            server.createContext("/", new IndexHandler("/web/index.html"));
            server.createContext("/jobs", new IndexHandler("/web/jobs.html"));
            server.createContext("/logs", new IndexHandler("/web/logs.html"));
            server.createContext("/config", new IndexHandler("/web/config.html"));
            server.createContext("/assets", new StaticHandler());
            // api endpoints
            server.createContext("/api/status", WebServer::handleStatus);
            server.createContext("/api/jobs", WebServer::handleJobs);
            server.createContext("/api/workers", WebServer::handleWorkers);
            server.createContext("/api/logs", WebServer::handleLogs);
            server.createContext("/api/dlq/retry", WebServer::handleDlqRetry);
            server.createContext("/api/config/list", WebServer::handleConfigList);
            server.createContext("/api/config/set", WebServer::handleConfigSet);

            server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
            server.start();
            System.out.println("Web server listening on http://localhost:" + port);
        } catch (IOException e) {
            throw new RuntimeException("Failed to start web server", e);
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
            System.out.println("Web server stopped");
        }
    }

    private static void setJsonHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    }

    private static Map<String, List<String>> queryParams(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        Map<String, List<String>> map = new HashMap<>();
        if (q == null || q.isEmpty())
            return map;
        for (String part : q.split("&")) {
            String[] kv = part.split("=", 2);
            String k = urlDecode(kv[0]);
            String v = kv.length > 1 ? urlDecode(kv[1]) : "";
            map.computeIfAbsent(k, _k -> new ArrayList<>()).add(v);
        }
        return map;
    }

    private static String qp(Map<String, List<String>> m, String key, String def) {
        List<String> v = m.get(key);
        return v == null || v.isEmpty() ? def : v.get(0);
    }

    private static String urlDecode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    private static void handleStatus(HttpExchange ex) throws IOException {
        try {
            setJsonHeaders(ex);
            JobRepository repo = new JobRepository(2);
            WorkerRegistry wr = new WorkerRegistry();
            Map<String, Integer> states = repo.stateCounts();
            WorkerRegistry.Counts counts = wr.counts();
            // also provide recent jobs summary for dashboard (last 10 by created desc)
            List<Job> recent = repo.listJobsByState(null);
            if (recent.size() > 10)
                recent = recent.subList(0, 10);
            List<Map<String, Object>> recentArr = new ArrayList<>();
            for (Job j : recent) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", j.getId());
                m.put("state", j.getState().name());
                m.put("priority", j.getPriority());
                m.put("attempts", j.getAttempts());
                recentArr.add(m);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("states", states);
            body.put("workers", Map.of("idle", counts.idle(), "busy", counts.busy()));
            body.put("recent", recentArr);
            String json = gson.toJson(body);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            respondError(ex, e);
        }
    }

    private static void handleJobs(HttpExchange ex) throws IOException {
        try {
            setJsonHeaders(ex);
            Map<String, List<String>> qp = queryParams(ex);
            String state = qp(qp, "state", null);
            String limitStr = qp(qp, "limit", "100");
            int limit = 100;
            try {
                limit = Integer.parseInt(limitStr);
            } catch (Exception ignored) {
            }
            JobRepository repo = new JobRepository(2);
            List<Job> jobs = repo.listJobsByState(state);
            if (jobs.size() > limit)
                jobs = jobs.subList(0, limit);
            List<Map<String, Object>> arr = jobs.stream().map(j -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", j.getId());
                m.put("state", j.getState().name());
                m.put("attempts", j.getAttempts());
                m.put("maxRetries", j.getMaxRetries());
                m.put("priority", j.getPriority());
                m.put("command", j.getCommand());
                m.put("availableAt", j.getAvailableAtEpoch());
                m.put("runAt", j.getRunAtEpoch());
                m.put("timeoutSeconds", j.getTimeoutSeconds());
                m.put("lastError", j.getLastError());
                return m;
            }).collect(Collectors.toList());
            byte[] bytes = gson.toJson(arr).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            respondError(ex, e);
        }
    }

    private static void handleWorkers(HttpExchange ex) throws IOException {
        try {
            setJsonHeaders(ex);
            WorkerRegistry wr = new WorkerRegistry();
            List<WorkerRegistry.WorkerInfo> list = wr.list();
            byte[] bytes = gson.toJson(list).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            respondError(ex, e);
        }
    }

    private static List<String> tailLines(Path path, int n) throws IOException {
        if (!Files.exists(path))
            return List.of();
        List<String> lines = Files.readAllLines(path);
        if (lines.size() <= n)
            return lines;
        return lines.subList(lines.size() - n, lines.size());
    }

    private static void handleLogs(HttpExchange ex) throws IOException {
        try {
            setJsonHeaders(ex);
            Map<String, List<String>> qp = queryParams(ex);
            String wid = qp(qp, "worker", null);
            int n = 200;
            try {
                n = Integer.parseInt(qp(qp, "n", "200"));
            } catch (Exception ignored) {
            }
            Path logsDir = Paths.get(Database.baseDir(), "logs");
            List<Map<String, Object>> result = new ArrayList<>();
            if (wid != null && !wid.isBlank()) {
                Path f = logsDir.resolve(wid + ".log");
                List<String> lines = tailLines(f, n);
                result.add(Map.of("worker", wid, "lines", lines));
            } else {
                try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(logsDir, "*.log")) {
                    for (Path p : ds) {
                        String name = p.getFileName().toString();
                        String worker = name.substring(0, name.length() - 4);
                        List<String> lines = tailLines(p, n);
                        result.add(Map.of("worker", worker, "lines", lines));
                    }
                }
            }
            byte[] bytes = gson.toJson(result).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            respondError(ex, e);
        }
    }

    private static void handleDlqRetry(HttpExchange ex) throws IOException {
        try {
            setJsonHeaders(ex);
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, List<String>> qp = queryParams(ex);
            String id = qp(qp, "id", null);
            if (id == null) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            JobRepository repo = new JobRepository(2);
            repo.retryDeadJob(id);
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            respondError(ex, e);
        }
    }

    private static void respondError(HttpExchange ex, Exception e) throws IOException {
        e.printStackTrace();
        setJsonHeaders(ex);
        Map<String, Object> body = Map.of(
                "error", e.getClass().getSimpleName(),
                "message", e.getMessage(),
                "timestamp", Instant.now().toString());
        byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(500, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static class IndexHandler implements HttpHandler {
        private final String resource;

        IndexHandler(String resource) {
            this.resource = resource;
        }

        @Override
        public void handle(HttpExchange ex) throws IOException {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            byte[] data = readResource(resource);
            if (data == null) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath(); // /assets/...
            String resPath = "/web" + path.replaceFirst("/assets", "");
            byte[] data = readResource(resPath);
            if (data == null) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            String mime = guessMime(resPath);
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private static byte[] readResource(String res) throws IOException {
        try (InputStream is = WebServer.class.getResourceAsStream(res)) {
            if (is == null)
                return null;
            return is.readAllBytes();
        }
    }

    private static String guessMime(String res) {
        if (res.endsWith(".css"))
            return "text/css; charset=utf-8";
        if (res.endsWith(".js"))
            return "application/javascript; charset=utf-8";
        if (res.endsWith(".html"))
            return "text/html; charset=utf-8";
        if (res.endsWith(".svg"))
            return "image/svg+xml";
        return "application/octet-stream";
    }

    private static void handleConfigList(HttpExchange ex) throws IOException {
        try {
            setJsonHeaders(ex);
            ConfigRepository cr = new ConfigRepository();
            Map<String, String> all = cr.listAll();
            byte[] bytes = gson.toJson(all).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            respondError(ex, e);
        }
    }

    private static void handleConfigSet(HttpExchange ex) throws IOException {
        try {
            setJsonHeaders(ex);
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, List<String>> qp = queryParams(ex);
            String key = qp(qp, "key", null);
            String value = qp(qp, "value", null);
            if (key == null || value == null) {
                ex.sendResponseHeaders(400, -1);
                return;
            }
            new ConfigRepository().set(key, value);
            byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception e) {
            respondError(ex, e);
        }
    }
}
