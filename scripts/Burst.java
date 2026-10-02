import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * On-sale stampede against a running service.
 *
 *   java scripts/Burst.java <BASE_URL> [requests] [users] [inFlight]
 *
 * Defaults: 20000 requests, 2000 users, 1500 in flight.
 * Admin credentials come from ADMIN_USERNAME / ADMIN_PASSWORD.
 */
public class Burst {

    static final String[] HOT_SEATS = {"A12", "A13"};
    static final int SEAT_COUNT = 1000;
    static final double HOT_SHARE = 0.70;
    static final double REPLAY_SHARE = 0.10;

    static HttpClient client;
    static String baseUrl;

    static final Map<String, AtomicLong> byOutcome = new ConcurrentHashMap<>();
    static final AtomicLong serverErrors = new AtomicLong();
    static final AtomicLong transportErrors = new AtomicLong();
    static final List<Long> latenciesMicros = java.util.Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        baseUrl = args.length > 0 ? args[0].replaceAll("/$", "") : "http://localhost:8080";
        int requests = args.length > 1 ? Integer.parseInt(args[1]) : 20_000;
        int userCount = args.length > 2 ? Integer.parseInt(args[2]) : 2_000;
        int inFlight = args.length > 3 ? Integer.parseInt(args[3]) : 1_500;

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        banner(requests, userCount, inFlight);

        String adminToken = loginAdmin();
        String showId = createShow(adminToken);
        List<String> tokens = registerUsers(userCount);

        System.out.println("\n--- firing " + requests + " concurrent reservations ---");
        long wallStart = System.nanoTime();
        fire(requests, tokens, showId, inFlight);
        long wallMillis = (System.nanoTime() - wallStart) / 1_000_000;

        report(requests, wallMillis);
        reconcile(showId);
    }

    // ---------- setup ----------

    static void banner(int requests, int users, int inFlight) {
        System.out.println("target      " + baseUrl);
        System.out.println("requests    " + requests);
        System.out.println("users       " + users);
        System.out.println("in flight   " + inFlight);
        System.out.println("hot seats   " + String.join(", ", HOT_SEATS) + "  (" + (int) (HOT_SHARE * 100) + "% of traffic)");
    }

    static String loginAdmin() throws Exception {
        String username = envOr("ADMIN_USERNAME", "admin");
        String password = System.getenv("ADMIN_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("ADMIN_PASSWORD must be set to create the show");
        }
        HttpResponse<String> response = post("/auth/login", null, null,
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        if (response.statusCode() != 200) {
            throw new IllegalStateException("admin login failed: " + response.statusCode() + " " + response.body());
        }
        System.out.println("\nadmin login ok");
        return field(response.body(), "token");
    }

    static String createShow(String adminToken) throws Exception {
        StringBuilder seats = new StringBuilder();
        for (String hot : HOT_SEATS) {
            seats.append(seats.isEmpty() ? "" : ",").append('"').append(hot).append('"');
        }
        for (int i = 1; i <= SEAT_COUNT - HOT_SEATS.length; i++) {
            seats.append(",\"S").append(i).append('"');
        }

        String body = "{\"name\":\"burst-" + System.currentTimeMillis() + "\","
                + "\"seats\":[" + seats + "],"
                + "\"price_paise\":25000,"
                + "\"per_user_limit\":4}";

        HttpResponse<String> response = post("/shows", adminToken, null, body);
        if (response.statusCode() != 201) {
            throw new IllegalStateException("create show failed: " + response.statusCode() + " " + response.body());
        }
        String showId = field(response.body(), "id");
        System.out.println("show created " + showId + " with " + SEAT_COUNT + " seats");
        return showId;
    }

    static List<String> registerUsers(int count) throws Exception {
        List<String> tokens = java.util.Collections.synchronizedList(new ArrayList<>(count));
        CountDownLatch done = new CountDownLatch(count);
        long stamp = System.currentTimeMillis();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        HttpResponse<String> response = post("/auth/register", null, null,
                                "{\"username\":\"burst-" + stamp + "-" + index + "\",\"password\":\"password123\"}");
                        if (response.statusCode() == 201) {
                            tokens.add(field(response.body(), "token"));
                        }
                    } catch (Exception ignored) {
                        // a failed registration just means one fewer buyer
                    } finally {
                        done.countDown();
                    }
                });
            }
            done.await();
        }
        if (tokens.isEmpty()) {
            throw new IllegalStateException("no users registered");
        }
        System.out.println("registered " + tokens.size() + " users");
        return tokens;
    }

    // ---------- the stampede ----------

    static void fire(int requests, List<String> tokens, String showId, int inFlight) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(requests);
        Semaphore permits = new Semaphore(inFlight);
        String path = "/shows/" + showId + "/reserve";
        long stamp = System.currentTimeMillis();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < requests; i++) {
                int index = i;
                pool.submit(() -> {
                    try {
                        gate.await();
                        permits.acquire();

                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        String token = tokens.get(random.nextInt(tokens.size()));

                        String seat = random.nextDouble() < HOT_SHARE
                                ? HOT_SEATS[random.nextInt(HOT_SEATS.length)]
                                : "S" + random.nextInt(1, SEAT_COUNT - HOT_SEATS.length + 1);

                        // a slice of traffic replays an earlier key verbatim
                        String key = random.nextDouble() < REPLAY_SHARE
                                ? "replay-" + stamp + "-" + (index % 50)
                                : "key-" + stamp + "-" + index;

                        String body = "{\"seats\":[\"" + seat + "\"],\"idempotency_key\":\"" + key + "\","
                                + "\"user_id\":\"00000000-0000-0000-0000-000000000000\"}";

                        long start = System.nanoTime();
                        HttpResponse<String> response = post(path, token, key, body);
                        latenciesMicros.add((System.nanoTime() - start) / 1_000);

                        record(response);
                    } catch (Exception e) {
                        transportErrors.incrementAndGet();
                        count("transport_error");
                    } finally {
                        permits.release();
                        done.countDown();
                    }
                });
            }
            gate.countDown();
            done.await();
        }
    }

    static void record(HttpResponse<String> response) {
        int status = response.statusCode();
        if (status >= 500) {
            serverErrors.incrementAndGet();
            count("5xx:" + status);
            return;
        }
        if (status == 201 || status == 200) {
            count("confirmed");
            return;
        }
        String reason = field(response.body(), "reason");
        count(status + ":" + (reason == null ? "unknown" : reason));
    }

    // ---------- reporting ----------

    static void report(int requests, long wallMillis) {
        System.out.println("\n--- outcome distribution ---");
        byOutcome.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> System.out.printf("%-34s %,8d%n", e.getKey(), e.getValue().get()));

        List<Long> sorted = new ArrayList<>(latenciesMicros);
        java.util.Collections.sort(sorted);

        System.out.println("\n--- throughput and latency ---");
        System.out.printf("wall clock                     %,8d ms%n", wallMillis);
        System.out.printf("throughput                     %,8d req/s%n",
                wallMillis == 0 ? 0 : (requests * 1000L / wallMillis));
        if (!sorted.isEmpty()) {
            System.out.printf("p50                            %,8.1f ms%n", percentile(sorted, 50) / 1000.0);
            System.out.printf("p95                            %,8.1f ms%n", percentile(sorted, 95) / 1000.0);
            System.out.printf("p99                            %,8.1f ms%n", percentile(sorted, 99) / 1000.0);
            System.out.printf("max                            %,8.1f ms%n", sorted.getLast() / 1000.0);
        }

        System.out.println("\n--- correctness gates ---");
        gate("zero 5xx", serverErrors.get() == 0, serverErrors.get() + " server errors");
        gate("zero transport failures", transportErrors.get() == 0, transportErrors.get() + " failed to send");
    }

    static void reconcile(String showId) throws Exception {
        HttpResponse<String> response = get("/shows/" + showId);
        String body = response.body();

        long available = number(body, "available_count");
        long held = number(body, "held_count");
        long confirmed = number(body, "confirmed_count");
        long total = number(body, "total_seats");

        System.out.println("\n--- reconciliation ---");
        System.out.printf("available %,d + held %,d + confirmed %,d = %,d   (total_seats %,d)%n",
                available, held, confirmed, available + held + confirmed, total);
        gate("available + held + confirmed == total_seats", available + held + confirmed == total, "MISMATCH");

        long hotConfirmed = 0;
        for (String hot : HOT_SEATS) {
            Matcher matcher = Pattern
                    .compile("\\{\"label\":\"" + hot + "\",\"status\":\"(held|confirmed)\"}")
                    .matcher(body.replaceAll("\\s+", ""));
            if (matcher.find()) {
                hotConfirmed++;
            }
        }
        gate("every hot seat claimed exactly once", hotConfirmed == HOT_SEATS.length,
                hotConfirmed + " of " + HOT_SEATS.length + " hot seats claimed");
    }

    static void gate(String name, boolean passed, String detail) {
        System.out.printf("%-46s %s%n", name, passed ? "PASS" : "FAIL  (" + detail + ")");
        if (!passed) {
            System.exit(1);
        }
    }

    // ---------- plumbing ----------

    static HttpResponse<String> post(String path, String token, String idempotencyKey, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            builder.header("Idempotency-Key", idempotencyKey);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static void count(String key) {
        byOutcome.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    static String field(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json == null ? "" : json);
        return matcher.find() ? matcher.group(1) : null;
    }

    static long number(String json, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*(\\d+)").matcher(json == null ? "" : json);
        return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
    }

    static long percentile(List<Long> sorted, int p) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * p / 100.0) - 1));
    }

    static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
