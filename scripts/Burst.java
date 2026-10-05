import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * On-sale stampede against BASE_URL. One fresh show, one burst that mixes hot-seat storms,
 * front-weighted traffic, same-key retries and one user firing past the limit, while a poller
 * checks the invariant. Then spoof/cancel checks and reconciliation against GET /shows/{id} and
 * /actuator/prometheus. Exit 1 on any FAIL.
 *
 * <p>Needs /auth/token enabled (DEV_TOKEN_ENABLED=true). Prometheus deltas assume one instance and
 * no other traffic during the run.
 *
 * <p>Usage: java scripts/Burst.java BASE_URL. Env: REQUESTS (20000), CONCURRENCY (= REQUESTS).
 */
public class Burst {

    static final int ROWS = 20, COLS = 50, HOT_EACH = 500, IDEM_N = 20, LIMIT_N = 10, LIMIT = 4;
    static final List<String> HOT = List.of("A10", "A11", "A12", "A13", "A14");
    static final String[] REASONS = {
            "seat_taken", "per_user_limit", "idempotency_conflict", "busy", "overloaded"
    };

    // HTTP/1.1: over h2 the JDK client multiplexes everything onto one connection and fails with
    // "too many concurrent streams" at the server's stream cap instead of opening another one
    static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
    static final String RUN = Long.toString(System.currentTimeMillis(), 36);
    static final AtomicInteger SEQ = new AtomicInteger();
    static final AtomicInteger RETRIES = new AtomicInteger();
    static String base;

    record Plan(String group, String user, List<String> seats, String key) {}

    /** status -1 = transport error (refused, reset, client timeout): no server answer. */
    record Out(Plan plan, int status, String body) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: java scripts/Burst.java <BASE_URL>");
            System.exit(2);
        }
        base = args[0].replaceAll("/+$", "");
        int requests = Integer.parseInt(System.getenv().getOrDefault("REQUESTS", "20000"));
        int concurrency =
                Integer.parseInt(System.getenv().getOrDefault("CONCURRENCY", String.valueOf(requests)));

        waitReady();
        var seatIds = new ArrayList<String>();
        for (int r = 0; r < ROWS; r++) {
            for (int c = 1; c <= COLS; c++) {
                seatIds.add((char) ('A' + r) + "" + c);
            }
        }
        // dedicated rows nobody else targets: X spoof/cancel, Y idempotency, Z per-user limit
        for (var row : List.of("X", "Y", "Z")) {
            for (int c = 1; c <= 10; c++) {
                seatIds.add(row + c);
            }
        }
        var admin = token("admin-" + RUN, "ADMIN");
        var created =
                call("POST", "/shows", admin,
                        "{\"name\":\"burst-" + RUN + "\",\"price_paise\":50000,\"seats\":" + arr(seatIds) + "}");
        if (created[0] != null && !created[0].equals("201")) {
            throw new IllegalStateException("create show: " + created[0] + " " + created[1]);
        }
        long show = num(created[1], "id");

        var plan = new ArrayList<Plan>();
        int u = 0;
        for (var seat : HOT) {
            for (int i = 0; i < HOT_EACH; i++) {
                plan.add(new Plan("hot", "h" + RUN + "-" + u++, List.of(seat), "k"));
            }
        }
        for (int i = 0; i < IDEM_N; i++) {
            plan.add(new Plan("idem", "idem-" + RUN, List.of("Y1", "Y2"), "same-key"));
        }
        for (int i = 0; i < LIMIT_N; i++) {
            plan.add(new Plan("limit", "lim-" + RUN, List.of("Z" + (i + 1)), "k" + i));
        }
        var rnd = new Random();
        for (int i = plan.size(); i < requests; i++) {
            int row = (int) (ROWS * Math.pow(rnd.nextDouble(), 2)); // r^2: front rows hottest
            int n = 1 + rnd.nextInt(4);
            int col = 1 + rnd.nextInt(COLS - n + 1);
            var seats = new ArrayList<String>();
            for (int c = col; c < col + n; c++) {
                seats.add((char) ('A' + row) + "" + c);
            }
            plan.add(new Plan("broad", "b" + RUN + "-" + u++, seats, "k"));
        }
        Collections.shuffle(plan);

        // ponytail: one /auth/token call per user; sign locally from JWT_SECRET if minting is too slow
        long t = System.nanoTime();
        var tokens = new ConcurrentHashMap<String, String>();
        var mintCap = new Semaphore(200);
        var mints = new ArrayList<Future<?>>();
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var user : plan.stream().map(Plan::user).distinct().toList()) {
                mints.add(ex.submit(
                        () -> {
                            mintCap.acquire();
                            try {
                                tokens.put(user, token(user, "USER"));
                            } finally {
                                mintCap.release();
                            }
                            return null;
                        }));
            }
        }
        // a failed mint aborts the run: a missing token would sneak in as 401s and vacuous passes
        for (var f : mints) {
            f.get();
        }
        System.out.printf(
                "run %s  show %d  %d tokens in %.1fs  (logs: grep burst-%s)%n",
                RUN, show, tokens.size(), secs(t), RUN);
        var spoofer = token("spoof-" + RUN, "USER");
        var victim = token("victim-" + RUN, "USER");

        var promBefore = call("GET", "/actuator/prometheus", null, null)[1];
        var stop = new AtomicBoolean();
        var polls = new AtomicInteger();
        var poll5xx = new AtomicInteger();
        var drift = new ConcurrentLinkedQueue<String>();
        var poller =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    while (!stop.get()) {
                                        var r = call("GET", "/shows/" + show, null, null);
                                        if ("200".equals(r[0])) {
                                            polls.incrementAndGet();
                                            long sum = num(r[1], "available") + num(r[1], "held") + num(r[1], "confirmed");
                                            if (sum != num(r[1], "total_seats")) {
                                                drift.add(sum + " != " + num(r[1], "total_seats"));
                                            }
                                        } else if (r[0] != null && r[0].startsWith("5")) {
                                            poll5xx.incrementAndGet();
                                        }
                                        try {
                                            Thread.sleep(250);
                                        } catch (InterruptedException e) {
                                            return;
                                        }
                                    }
                                });

        var outs = new ConcurrentLinkedQueue<Out>();
        var gate = new CountDownLatch(1);
        var cap = new Semaphore(concurrency);
        try (var ex = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var p : plan) {
                ex.submit(
                        () -> {
                            gate.await();
                            cap.acquire();
                            try {
                                outs.add(reserve(p, tokens.get(p.user()), show, null));
                            } finally {
                                cap.release();
                            }
                            return null;
                        });
            }
            t = System.nanoTime();
            gate.countDown();
        }
        double burstSecs = secs(t);
        stop.set(true);
        poller.join();

        // after the burst, so ordering is deterministic
        var idemConflict =
                reserve(new Plan("post", "idem-" + RUN, List.of("Y3"), "same-key"),
                        tokens.get("idem-" + RUN), show, null);
        var spoof =
                reserve(new Plan("post", "spoof-" + RUN, List.of("X1"), "k"), spoofer, show,
                        "victim-" + RUN);
        var victimRes = reserve(new Plan("post", "victim-" + RUN, List.of("X2"), "k"), victim, show, null);
        var cancel =
                call("POST", "/reservations/" + str(victimRes.body(), "reservation_id") + "/cancel",
                        spoofer, null);
        outs.addAll(List.of(idemConflict, spoof, victimRes));

        Thread.sleep(6000); // seats gauge refreshes every 5s
        var fin = call("GET", "/shows/" + show, null, null)[1];
        var promAfter = call("GET", "/actuator/prometheus", null, null)[1];

        // ---- outcome table
        var groups = List.of("hot", "broad", "idem", "limit", "post");
        System.out.printf(
                "%nburst: %d requests in %.1fs (%.0f req/s), concurrency %d, %d transport retries%n%n",
                plan.size(), burstSecs, plan.size() / burstSecs, concurrency, RETRIES.get());
        System.out.printf(
                "%-6s %6s %6s %6s %10s %8s %9s %6s %6s %6s %5s %9s%n",
                "group", "sent", "201", "replay", "seat_taken", "per_user", "idem_conf", "busy", "429",
                "4xx", "5xx", "transport");
        var fresh = new HashMap<String, List<String>>(); // reservation_id -> seats, first 201 only
        long replays = 0;
        for (var g : groups) {
            var gs = outs.stream().filter(o -> o.plan().group().equals(g)).toList();
            long ok = gs.stream().filter(o -> o.status() == 201).count();
            var ids = new HashSet<String>();
            for (var o : gs) {
                if (o.status() == 201) {
                    var id = str(o.body(), "reservation_id");
                    ids.add(id);
                    fresh.putIfAbsent(id, list(o.body(), "seats"));
                }
            }
            replays += ok - ids.size();
            System.out.printf(
                    "%-6s %6d %6d %6d %10d %8d %9d %6d %6d %6d %5d %9d%n",
                    g, gs.size(), ids.size(), ok - ids.size(),
                    reason(gs, 409, "seat_taken"), reason(gs, 409, "per_user_limit"),
                    reason(gs, 409, "idempotency_conflict"), reason(gs, 409, "busy"),
                    gs.stream().filter(o -> o.status() == 429).count(),
                    gs.stream().filter(o -> o.status() >= 400 && o.status() < 500
                            && o.status() != 409 && o.status() != 429).count(),
                    gs.stream().filter(o -> o.status() >= 500).count(),
                    gs.stream().filter(o -> o.status() == -1).count());
        }

        // ---- correctness bar
        var checks = new LinkedHashMap<String, Boolean>();
        var owners = new HashMap<String, Integer>(); // seat -> fresh reservations holding it
        fresh.values().forEach(ss -> ss.forEach(s -> owners.merge(s, 1, Integer::sum)));
        checks.put("1  no seat confirmed to two reservations",
                owners.values().stream().allMatch(c -> c == 1));
        for (var seat : HOT) {
            var storm = outs.stream().filter(o -> o.plan().group().equals("hot")
                    && o.plan().seats().get(0).equals(seat)).toList();
            checks.put("1  hot " + seat + ": exactly one 201, every other storm request 409",
                    owners.getOrDefault(seat, 0) == 1
                            && storm.stream().allMatch(o -> o.status() == 201 || o.status() == 409));
        }
        long fives = outs.stream().filter(o -> o.status() >= 500).count() + poll5xx.get()
                + (cancel[0] != null && cancel[0].startsWith("5") ? 1 : 0);
        checks.put("2  zero 5xx (reserve, cancel, GET poller)", fives == 0);
        checks.put("3  invariant during burst (" + polls.get() + " polls, " + drift.size() + " off)",
                polls.get() > 0 && drift.isEmpty());
        checks.put("3  invariant after: available+held+confirmed == total_seats",
                num(fin, "available") + num(fin, "held") + num(fin, "confirmed") == num(fin, "total_seats"));
        var sold = new HashSet<String>();
        fresh.values().forEach(sold::addAll);
        checks.put("3  GET confirmed seats == seats in 201 responses",
                new HashSet<>(list(fin, "confirmed")).equals(sold));
        var idemIds = outs.stream()
                .filter(o -> o.plan().group().equals("idem") && o.status() == 201)
                .map(o -> str(o.body(), "reservation_id")).collect(Collectors.toSet());
        checks.put("4  same key x" + IDEM_N + " in parallel -> one reservation", idemIds.size() == 1);
        checks.put("4  same key, different seats -> 409 idempotency_conflict",
                idemConflict.status() == 409
                        && "idempotency_conflict".equals(str(idemConflict.body(), "reason")));
        long limitWon = outs.stream()
                .filter(o -> o.plan().group().equals("limit") && o.status() == 201).count();
        long limitHeld = list(fin, "confirmed").stream().filter(s -> s.startsWith("Z")).count();
        checks.put("5  one user, " + LIMIT_N + " parallel, limit " + LIMIT + " -> at most " + LIMIT
                + " held (" + limitHeld + ")", limitWon <= LIMIT && limitHeld <= LIMIT);
        checks.put("6  spoofed body user_id -> acts as token user",
                spoof.status() == 201 && ("spoof-" + RUN).equals(str(spoof.body(), "user_id")));
        checks.put("6  cancel someone else's reservation -> 404, seat stays confirmed",
                "404".equals(cancel[0]) && list(fin, "confirmed").contains("X2"));

        // ---- metrics reconcile with what the client saw (deltas: counters reset on restart)
        checks.put("M  reservations_confirmed_total delta == fresh 201s (" + fresh.size() + ")",
                delta(promBefore, promAfter, "reservations_confirmed_total") == fresh.size());
        checks.put("M  declined{idempotent_replay} delta == replayed 201s (" + replays + ")",
                delta(promBefore, promAfter, "reservations_declined_total",
                        "reason=\"idempotent_replay\"") == replays);
        for (var r : REASONS) {
            long seen = reason(List.copyOf(outs), r.equals("overloaded") ? 429 : 409, r);
            checks.put("M  declined{" + r + "} delta == client count (" + seen + ")",
                    delta(promBefore, promAfter, "reservations_declined_total",
                            "reason=\"" + r + "\"") == seen);
        }
        for (var st : List.of("available", "held", "confirmed")) {
            checks.put("M  seats{status=" + st + "} gauge == GET count (" + num(fin, st) + ")",
                    metric(promAfter, "seats", "show_id=\"" + show + "\"", "status=\"" + st + "\"")
                            == num(fin, st));
        }

        System.out.println();
        checks.forEach((k, v) -> System.out.println((v ? "PASS  " : "FAIL  ") + k));
        long transport = outs.stream().filter(o -> o.status() == -1).count();
        if (transport > 0) {
            System.out.println("WARN  " + transport + " transport errors (client side, no server answer): "
                    + outs.stream().filter(o -> o.status() == -1).findFirst().get().body());
        }
        boolean pass = !checks.containsValue(false);
        System.out.println(pass ? "\nRESULT: PASS" : "\nRESULT: FAIL");
        System.exit(pass ? 0 : 1);
    }

    static Out reserve(Plan p, String token, long show, String spoofUser) {
        var body = "{\"seats\":" + arr(p.seats()) + ",\"idempotency_key\":\"" + p.key() + "\""
                + (spoofUser == null ? "" : ",\"user_id\":\"" + spoofUser + "\"") + "}";
        var r = call("POST", "/shows/" + show + "/reserve", token, body);
        // no answer (connection dropped in front of the app): retry with the same key, as a real
        // client would. If the first attempt committed, the retry replays it, never books twice
        for (int i = 0; i < 3 && r[0] == null; i++) {
            RETRIES.incrementAndGet();
            r = call("POST", "/shows/" + show + "/reserve", token, body);
        }
        return new Out(p, r[0] == null ? -1 : Integer.parseInt(r[0]), r[1]);
    }

    /** {status, body}; status null on a transport error, body then holds the exception. */
    static String[] call(String method, String path, String token, String json) {
        var b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(90))
                .header("X-Request-Id", "burst-" + RUN + "-" + SEQ.incrementAndGet())
                .method(method, json == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) {
            b.header("Content-Type", "application/json");
        }
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            var r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new String[] {String.valueOf(r.statusCode()), r.body()};
        } catch (Exception e) {
            return new String[] {null, e.toString()};
        }
    }

    static String token(String sub, String role) {
        var r = call("POST", "/auth/token", null, "{\"sub\":\"" + sub + "\",\"role\":\"" + role + "\"}");
        if (!"200".equals(r[0])) {
            throw new IllegalStateException("token " + r[0] + " " + r[1]);
        }
        return str(r[1], "token");
    }

    /** Cold start (free tier spins down): wait up to 3 min for readiness. */
    static void waitReady() throws InterruptedException {
        for (int i = 0; ; i++) {
            var r = call("GET", "/actuator/health/readiness", null, null);
            if ("200".equals(r[0])) {
                return;
            }
            if (i == 90) {
                System.err.println("not ready: " + r[0] + " " + r[1]);
                System.exit(2);
            }
            Thread.sleep(2000);
        }
    }

    static long reason(List<Out> outs, int status, String reason) {
        return outs.stream()
                .filter(o -> o.status() == status && reason.equals(str(o.body(), "reason")))
                .count();
    }

    static long delta(String before, String after, String name, String... labels) {
        return Math.round(metric(after, name, labels) - metric(before, name, labels));
    }

    /** First Prometheus text line for {@code name} carrying every label; 0 if absent. */
    static double metric(String text, String name, String... labels) {
        for (var line : text.split("\n")) {
            if (line.startsWith(name + "{") || line.startsWith(name + " ")) {
                if (Arrays.stream(labels).allMatch(line::contains)) {
                    return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
                }
            }
        }
        return 0;
    }

    // flat JSON only: enough for this API's bodies, no parser dependency
    static String str(String json, String field) {
        var m = Pattern.compile("\"" + field + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static long num(String json, String field) {
        var m = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        return m.find() ? Long.parseLong(m.group(1)) : -1;
    }

    static List<String> list(String json, String field) {
        var m = Pattern.compile("\"" + field + "\":\\[([^\\]]*)]").matcher(json);
        if (!m.find() || m.group(1).isEmpty()) {
            return List.of();
        }
        return Arrays.stream(m.group(1).split(",")).map(s -> s.replace("\"", "")).toList();
    }

    static String arr(List<String> xs) {
        return xs.stream().map(x -> "\"" + x + "\"").collect(Collectors.joining(",", "[", "]"));
    }

    static double secs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e9;
    }
}


