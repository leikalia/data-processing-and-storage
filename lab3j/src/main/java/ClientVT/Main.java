package ClientVT;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class Main {

    private static JSONObject getJson(HttpClient http, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();

        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + resp.statusCode() + " for " + url);
        }
        return new JSONObject(resp.body());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: <host> <port>");
            System.exit(1);
        }

        String base = "http://" + args[0] + ":" + args[1];
        HttpClient http = HttpClient.newHttpClient();

        JSONObject root = getJson(http, base + "/");

        // список сообщений, потокобезопасный
        List<String> values = Collections.synchronizedList(new ArrayList<>());

        // нормализуем строку сразу при добавлении
        values.add(normalizeLine(root.getString("message")));

        Set<String> seen = ConcurrentHashMap.newKeySet();
        Set<String> frontier = ConcurrentHashMap.newKeySet();

        JSONArray startSucc = root.getJSONArray("successors");
        for (int i = 0; i < startSucc.length(); i++) {
            String s = startSucc.getString(i);
            if (seen.add(s)) {
                frontier.add(s);
            }
        }

        while (!frontier.isEmpty()) {
            Set<String> next = ConcurrentHashMap.newKeySet();

            try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> fs = new ArrayList<>(frontier.size());

                for (String id : frontier) {
                    fs.add(exec.submit(() -> {
                        int attempts = 0;
                        while (true) {
                            try {
                                JSONObject node = getJson(http, base + "/" + id);
                                // нормализуем пробелы и переводы строк в сообщении
                                values.add(normalizeLine(node.getString("message")));

                                JSONArray succ = node.getJSONArray("successors");
                                for (int j = 0; j < succ.length(); j++) {
                                    String s = succ.getString(j);
                                    if (seen.add(s)) {
                                        next.add(s);
                                    }
                                }
                                break; // успех
                            } catch (Exception ex) {
                                if (++attempts >= 3) {
                                    System.err.println("Give up on " + id + ": " + ex.getMessage());
                                    break;
                                }
                                try {
                                    Thread.sleep(50L * attempts);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }
                        }
                    }));
                }

                for (Future<?> f : fs) {
                    try {
                        f.get();
                    } catch (ExecutionException | InterruptedException e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }

            frontier = next;
        }

        // сортируем и печатаем с единообразными переводами строк
        Collections.sort(values);
        for (String line : values) {
            System.out.println(line);
        }
    }

    private static String normalizeLine(String line) {
        if (line == null) return "";
        String normalized = line
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .trim()
                .replaceAll(" +", " "); // заменяем двойные пробелы на один
        return normalized;
    }
}
