package com.zentro.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Disposable fixture is the only target. No base URL, token or database override is accepted. */
class PerformanceSmokeIT extends DisposableGatewayStack {
  private static final Properties CONFIG = new Properties();
  private static final Set<String> REFERENCES = ConcurrentHashMap.newKeySet();
  private static final AtomicInteger CREATED = new AtomicInteger();
  private static final List<String> FINDINGS = new ArrayList<>();
  private static String merchantToken;
  private static String courierToken;
  private static String courierSubject;
  private static String courierId;
  private static String courierUserId;
  private static String merchantSubject;
  private static String merchantSecret;
  private static String outsider;
  private static final String BODY = """
      {"sender":{"name":"SYNTHETIC SENDER","phone":"0700000000","address":"SYNTHETIC FIXTURE PICKUP"},
       "recipient":{"name":"SYNTHETIC RECIPIENT","phone":"0700000000","address":"SYNTHETIC FIXTURE DROPOFF"},
       "parcel":{"weightKg":1,"category":"GENERAL","size":"SMALL","description":"SYNTHETIC ONLY"},
       "destinationType":"OTHER_DISTRICT","isExpress":false}
      """;
  private record Sample(long nanos, int status, int bytes, boolean valid) {}
  @FunctionalInterface private interface Request { Sample run(int iteration) throws Exception; }

  @Test @Timeout(value = 15, unit = TimeUnit.MINUTES)
  void localGatewaySmoke() throws Exception {
    try (var input = getClass().getResourceAsStream("/performance-smoke.properties")) { CONFIG.load(input); }
    for (String key : CONFIG.stringPropertyNames()) {
      if (System.getProperty("perf." + key) != null) CONFIG.setProperty(key, System.getProperty("perf." + key));
    }
    assertThat(number("requests")).isBetween(25, 500);
    assertThat(number("warmup")).isBetween(1, 10);
    assertThat(number("levelTimeoutSeconds")).isBetween(1, 60);
    assertThat(number("requestTimeoutSeconds")).isBetween(1, 10);
    assertThat(CONFIG.getProperty("concurrency").split(",")).hasSizeLessThanOrEqualTo(4);
    assertThat(Double.parseDouble(CONFIG.getProperty("degradationFactor"))).isGreaterThanOrEqualTo(1);
    for(String artifact:List.of("performance-growth-plans.txt","performance-plans.txt","performance-lag.txt","performance-findings.txt"))
      Files.deleteIfExists(MODULE.resolve("target/"+artifact));
    Files.writeString(MODULE.resolve("target/performance-results.csv"),
        "scenario,concurrency,requests,success,2xx,4xx,5xx,transportOrIntegrityErrors,reqPerSec,p50Ms,p95Ms,p99Ms,maxMs,errorPercent,payloadBytes,hostCpuLoad,hostUsedMemoryBytes\n");
    Files.writeString(MODULE.resolve("target/performance-growth.csv"),
        "endpoint,seedRows,status,responseRows,payloadBytes,elapsedMs,dbExecMs,dbCalls,hostUsedMemoryBytes\n");
    fixtureUsers();
    long seedStart = System.nanoTime();
    seedTracking();
    System.out.printf("PERF_SEED trackingParcels=10000 events=10000 elapsedMs=%.3f%n", elapsed(seedStart));
    inventoryGaps();
    growth();
    pagination();
    String selected = System.getProperty("perf.scenario", "all");
    assertThat(selected).isIn("all","booking","estimate","tracking","workload","mixed","failure","lists");
    if (selected.equals("all") || selected.equals("booking")) {
      ramp("booking", i -> booking(UUID.randomUUID().toString(), true));
      duplicate();
      assertThat(scalar("parcel_pricing_db", "parcel_pricing_user", "parcel-pricing-only",
          "SELECT count(*) FROM parcel.parcel WHERE customer_subject='" + customerId + "'"))
          .isEqualTo(CREATED.get());
      assertThat(scalar("parcel_pricing_db", "parcel_pricing_user", "parcel-pricing-only", """
          SELECT count(*) FROM parcel.outbox_event e JOIN parcel.parcel p ON p.id=e.aggregate_id
          WHERE p.customer_subject='%s' AND e.event_type='parcel.created'
          """.formatted(customerId))).isEqualTo(CREATED.get());
    }
    if (selected.equals("all") || selected.equals("estimate")) ramp("estimate", i -> request("POST",
        "/api/v1/parcels/estimate", customerToken,
        "{\"weightKg\":1,\"parcelSize\":\"SMALL\",\"destinationType\":\"OTHER_DISTRICT\"}", 200, null));
    if (selected.equals("all") || selected.equals("tracking")) {
      ramp("tracking-hot", i -> read("/api/v1/tracking/PERF-1", null, 200));
      ramp("tracking-cold", i -> read("/api/v1/tracking/PERF-" + (i * 137 % 10000 + 1), null, 200));
      ramp("tracking-unknown", i -> read("/api/v1/tracking/PERF-UNKNOWN", null, 404));
      ramp("mine-first", i -> read("/api/v1/tracking/mine?page=1&size=20", customerToken, 200));
      ramp("mine-deep", i -> read("/api/v1/tracking/mine?page=251&size=20", customerToken, 200));
    }
    if (selected.equals("all") || selected.equals("workload"))
      ramp("workload", i -> read("/api/v1/dispatch/workload", dispatcherToken, 200));
    if (selected.equals("all") || selected.equals("mixed")) {
      ramp("mixed-available", i -> i % 10 < 6 ? read("/api/v1/tracking/PERF-1", null, 200)
          : i % 10 < 9 ? read("/api/v1/dispatch/workload", dispatcherToken, 200)
          : booking(UUID.randomUUID().toString(), true));
      consumerLag();
    }
    if (selected.equals("all") || selected.equals("failure")) isolation();
    assertThat(scalar("parcel_pricing_db", "parcel_pricing_user", "parcel-pricing-only",
        "SELECT count(*) FROM parcel.parcel WHERE customer_subject='" + customerId + "'"))
        .isEqualTo(CREATED.get());
    assertThat(scalar("parcel_pricing_db", "parcel_pricing_user", "parcel-pricing-only", """
        SELECT count(*) FROM parcel.outbox_event e JOIN parcel.parcel p ON p.id=e.aggregate_id
        WHERE p.customer_subject='%s' AND e.event_type='parcel.created'
        """.formatted(customerId))).isEqualTo(CREATED.get());
    capturePlans();
    Files.writeString(MODULE.resolve("target/performance-findings.txt"), String.join("\n", FINDINGS));
    // Do not hide a missed bound by adjusting the configuration. Artifacts survive a failed run.
    assertThat(FINDINGS).as("machine-dependent smoke findings; inspect CSV before changing a bound").isEmpty();
  }

  private static int number(String key) { return Integer.parseInt(CONFIG.getProperty(key)); }
  private static double elapsed(long start) { return (System.nanoTime() - start) / 1_000_000d; }
  private static Sample read(String path, String token, int expected) throws Exception {
    return request("GET", path, token, null, expected, null);
  }
  private static Sample request(String method, String path, String token, String body, int expected, String key) throws Exception {
    var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080" + path))
        .timeout(Duration.ofSeconds(number("requestTimeoutSeconds"))).header("Content-Type", "application/json");
    if (token != null) builder.header("Authorization", "Bearer " + token);
    if (key != null) builder.header("Idempotency-Key", key);
    builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
    long start = System.nanoTime();
    var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    long nanos=System.nanoTime()-start;
    boolean valid = response.statusCode() == expected;
    if (method.equals("POST") && path.equals("/api/v1/parcels") && response.statusCode() == 201) {
      var json = JSON.readTree(response.body());
      valid &= !json.path("trackingRef").asText().isBlank() && json.hasNonNull("fee")
          && REFERENCES.add(json.path("trackingRef").asText());
      CREATED.incrementAndGet();
    }
    if(response.statusCode()==200 && path.startsWith("/api/v1/tracking/") && !path.contains("/mine"))
      valid &= JSON.readTree(response.body()).path("trackingRef").asText().equals(path.substring(path.lastIndexOf('/')+1));
    if(response.statusCode()==200 && path.equals("/api/v1/parcels/estimate"))
      valid &= JSON.readTree(response.body()).hasNonNull("fee");
    if(response.statusCode()==200 && path.contains("/tracking/mine"))
      valid &= JSON.readTree(response.body()).path("content").size()<=20;
    return new Sample(nanos, response.statusCode(), response.body().length, valid);
  }
  private static Sample booking(String key, boolean fresh) throws Exception {
    return request("POST", "/api/v1/parcels", customerToken, BODY, fresh ? 201 : 200, key);
  }
  private static void ramp(String name, Request request) throws Exception {
    // Refresh once outside all timed samples. No identity-provider traffic in measured requests.
    customerToken = tokenForClient(CUSTOMER_CLIENT, CUSTOMER_SECRET);
    dispatcherToken = tokenForClient(DISPATCHER_CLIENT, DISPATCHER_SECRET);
    for (int i = 0; i < number("warmup"); i++) {
      if (!request.run(i).valid()) { FINDINGS.add(name + ": warm-up failed; ramp aborted"); return; }
    }
    double previousP95 = 0;
    for (String level : CONFIG.getProperty("concurrency").split(",")) {
      int concurrency = Integer.parseInt(level);
      assertThat(concurrency).isIn(1, 5, 10, 25);
      List<Sample> samples = java.util.Collections.synchronizedList(new ArrayList<>());
      long start = System.nanoTime();
      try (var executor = Executors.newFixedThreadPool(concurrency)) {
        List<java.util.concurrent.Callable<Sample>> tasks = new ArrayList<>();
        var failures = new AtomicInteger();
        for (int i = 0; i < number("requests"); i++) {
          final int iteration = i;
          tasks.add(() -> {
            if (failures.get() >= 3) return null;
            long requestStart=System.nanoTime();
            try {
              Sample sample = request.run(iteration);
              if (!sample.valid()) failures.incrementAndGet();
              samples.add(sample);
              return sample;
            } catch (Exception e) {
              failures.incrementAndGet();
              Sample sample=new Sample(System.nanoTime()-requestStart,0,0,false);
              samples.add(sample);return sample;
            }
          });
        }
        executor.invokeAll(tasks, number("levelTimeoutSeconds"), TimeUnit.SECONDS);
      }
      if(samples.isEmpty()){FINDINGS.add(name+": no requests completed");return;}
      double seconds = elapsed(start) / 1000d;
      long[] latencies = samples.stream().mapToLong(Sample::nanos).sorted().toArray();
      long ok = samples.stream().filter(Sample::valid).count();
      long two = samples.stream().filter(s -> s.status / 100 == 2).count();
      long four = samples.stream().filter(s -> s.status / 100 == 4).count();
      long five = samples.stream().filter(s -> s.status / 100 == 5).count();
      long other = samples.stream().filter(s -> s.status == 0 || !s.valid && s.status / 100 < 4).count();
      double p95 = percentile(latencies, .95);
      var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      String row = String.format(java.util.Locale.ROOT,
          "%s,%d,%d,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.2f,%d,%.4f,%d%n",
          name, concurrency, samples.size(), ok, two, four, five, other, samples.size()/seconds,
          percentile(latencies,.50),p95,percentile(latencies,.99),percentile(latencies,1),
          (samples.size()-ok)*100d/samples.size(), samples.stream().mapToLong(Sample::bytes).sum(),
          os.getCpuLoad(), os.getTotalMemorySize()-os.getFreeMemorySize());
      Files.writeString(MODULE.resolve("target/performance-results.csv"), row, java.nio.file.StandardOpenOption.APPEND);
      System.out.print("PERF " + row);
      if (ok != samples.size() || p95 > number("p95Millis")) {
        FINDINGS.add(name + " concurrency=" + concurrency + " missed sanity bound; see CSV"); return;
      }
      if (previousP95 > 0 && p95 > previousP95 * Double.parseDouble(CONFIG.getProperty("degradationFactor"))) {
        FINDINGS.add(name + " ramp stopped: p95 degraded beyond configured factor"); return;
      }
      previousP95 = p95;
    }
  }
  // Nearest-rank percentile over every measured request, never percentiles of averages.
  static double percentile(long[] sorted, double fraction) {
    return sorted[Math.max(0, (int)Math.ceil(sorted.length * fraction) - 1)] / 1_000_000d;
  }

  private static Connection db(String database, String user, String password) throws Exception {
    return DriverManager.getConnection(jdbcUrl(database), user, password);
  }
  private static long scalar(String database, String user, String password, String sql) throws Exception {
    try (var connection = db(database,user,password); var statement = connection.createStatement(); var rs = statement.executeQuery(sql)) {
      rs.next(); return rs.getLong(1);
    }
  }
  private static void execute(String database, String user, String password, String sql) throws Exception {
    try (var connection = db(database,user,password); var statement = connection.createStatement()) {
      statement.setQueryTimeout(10); statement.execute(sql);
    }
  }
  private static void fixtureUsers() throws Exception {
    String secret = "fixture-" + UUID.randomUUID();
    courierSubject = createServiceClient("perf-courier", secret, "COURIER");
    courierToken = tokenForClient("perf-courier",secret);
    var provision = call("POST","/api/v1/courier/profile/provision",courierToken,null);
    assertThat(provision.statusCode()).isIn(200,201);
    try (var connection=db("identity_db","identity_user","identity-only");var st=connection.prepareStatement("""
        SELECT cp.id, u.id FROM identity.courier_profile cp JOIN identity.app_user u ON u.id=cp.app_user_id
        WHERE u.keycloak_subject=?
        """)) {
      st.setString(1,courierSubject);try(var rs=st.executeQuery()) { assertThat(rs.next()).isTrue();courierId=rs.getString(1);courierUserId=rs.getString(2); }
    }
    merchantSecret="fixture-"+UUID.randomUUID();
    merchantSubject=createServiceClient("perf-merchant",merchantSecret,"CUSTOMER");
    String before=tokenForClient("perf-merchant",merchantSecret);
    var application=call("POST","/api/v1/merchants",before,
        "{\"businessName\":\"SYNTHETIC PERF\",\"businessRegNo\":\"SYNTHETIC-PERF\",\"contactPhone\":\"0700000000\"}");
    assertThat(application.statusCode()).isIn(200,201);
    assertThat(call("PATCH","/api/v1/admin/merchants/"+JSON.readTree(application.body()).path("id").asText(),
        dispatcherToken,"{\"decision\":\"APPROVED\"}").statusCode()).isEqualTo(200);
    simulateManualMerchantRoleAssignment(merchantSubject);
    merchantToken=tokenForClient("perf-merchant",merchantSecret);
    String outsideSecret="fixture-"+UUID.randomUUID();
    createServiceClient("perf-outsider",outsideSecret,"CUSTOMER");
    outsider=tokenForClient("perf-outsider",outsideSecret);
  }
  private static void seedTracking() throws Exception {
    execute("tracking_reporting_db","tracking_user","tracking-only", """
        INSERT INTO tracking.tracking_parcel (tracking_ref,customer_subject,merchant_subject,sender_name,sender_phone,
        sender_address,recipient_name,recipient_phone,recipient_address,created_at)
        SELECT 'PERF-'||n, CASE WHEN n<=6000 THEN '%s' ELSE 'SYNTHETIC-C-'||(n%%49) END,
        'SYNTHETIC-M-'||(n%%20),'SYNTHETIC','0000000000','SYNTHETIC FIXTURE',
        'SYNTHETIC','0000000000','SYNTHETIC FIXTURE','2026-01-01'::timestamptz+n*interval '1 second'
        FROM generate_series(1,10000)n;
        INSERT INTO tracking.tracking_event (tracking_ref,status,title,occurred_at)
        SELECT tracking_ref,'CREATED','SYNTHETIC',created_at FROM tracking.tracking_parcel;
        ANALYZE tracking.tracking_parcel; ANALYZE tracking.tracking_event;
        """.formatted(customerId));
  }
  private static void inventoryGaps() throws Exception {
    var queue=call("GET","/api/v1/dispatch/queue",dispatcherToken,null);
    assertThat(queue.statusCode()).isEqualTo(502); // Gateway deliberately sanitizes upstream 503 to 502.
    System.out.println("PERF_GAP queue/assignment:gateway502,source503 DISPATCH_SOURCE_UNAVAILABLE; reports:not testable,no endpoint");
  }
  private static void duplicate() throws Exception {
    String key=UUID.randomUUID().toString();
    int before=CREATED.get();
    try(var pool=Executors.newFixedThreadPool(5)) {
      var tasks=new ArrayList<java.util.concurrent.Callable<Sample>>();
      for(int i=0;i<5;i++)tasks.add(()->request("POST","/api/v1/parcels",customerToken,BODY,200,key));
      var statuses=new ArrayList<Integer>();
      for(var f:pool.invokeAll(tasks,15,TimeUnit.SECONDS)) {assertThat(f.isCancelled()).isFalse();statuses.add(f.get().status);}
      assertThat(statuses.stream().filter(s->s==201).count()).isEqualTo(1);
      assertThat(statuses.stream().filter(s->s==200).count()).isEqualTo(4);
      assertThat(CREATED.get()-before).isEqualTo(1);
    }
  }
  private static void pagination() throws Exception {
    var expected=new java.util.HashSet<String>();
    try(var connection=db("tracking_reporting_db","tracking_user","tracking-only");var statement=connection.prepareStatement(
        "SELECT id::text FROM tracking.tracking_parcel WHERE customer_subject=?")) {
      statement.setString(1,customerId);try(var rows=statement.executeQuery()){while(rows.next())expected.add(rows.getString(1));}
    }
    var actual=new java.util.HashSet<String>();
    for(int p=1;p<=60;p++) {
      var response=call("GET","/api/v1/tracking/mine?page="+p+"&size=100",customerToken,null);
      assertThat(response.statusCode()).isEqualTo(200);
      var page=JSON.readTree(response.body());assertThat(page.path("total").asInt()).isEqualTo(6000);
      for(var item:page.path("content"))assertThat(actual.add(item.path("id").asText())).isTrue();
    }
    assertThat(actual).isEqualTo(expected);
    assertThat(JSON.readTree(call("GET","/api/v1/tracking/mine",outsider,null).body()).path("total").asInt()).isZero();
    assertThat(JSON.readTree(call("GET","/api/v1/tracking/mine?page=61&size=100",customerToken,null).body()).path("content")).isEmpty();
  }
  private static void growth() throws Exception {
    int previous=0;
    for(int volume:new int[]{100,1000,5000,10000}) {
      seedLists(previous+1,volume);previous=volume;
      for(String endpoint:List.of("/api/v1/admin/users","/api/v1/admin/merchants","/api/v1/admin/couriers",
          "/api/v1/dispatch/workload","/api/v1/courier/deliveries","/api/v1/merchant/orders",
          "/api/v1/zones","/api/v1/tracking/PERF-HISTORY","/api/v1/tracking/PERF-HISTORY/events")) {
        String token=endpoint.contains("/courier/deliveries")?courierToken:endpoint.contains("/merchant/orders")?merchantToken:dispatcherToken;
        double[] before=dbSignals(endpoint);
        long start=System.nanoTime();var response=call("GET",endpoint,token,null);double ms=elapsed(start);
        double[] after=dbSignals(endpoint);
        var body=JSON.readTree(response.body());int rows=body.isArray()?body.size():body.has("events")?body.path("events").size():body.path("items").size();
        var os=(com.sun.management.OperatingSystemMXBean)ManagementFactory.getOperatingSystemMXBean();
        String row=String.format(java.util.Locale.ROOT,"%s,%d,%d,%d,%d,%.3f,%.3f,%.0f,%d%n",endpoint,volume,response.statusCode(),rows,
            response.body().getBytes(StandardCharsets.UTF_8).length,ms,after[0]-before[0],after[1]-before[1],
            os.getTotalMemorySize()-os.getFreeMemorySize());
        Files.writeString(MODULE.resolve("target/performance-growth.csv"),row,java.nio.file.StandardOpenOption.APPEND);
        System.out.print("PERF_GROWTH "+row);
        assertThat(response.statusCode()).isEqualTo(200);
        var repeated=JSON.readTree(call("GET",endpoint,token,null).body());
        assertThat(repeated).as("same list query twice without writes: %s",endpoint).isEqualTo(body);
        String idField=endpoint.contains("courier/deliveries")?"parcelId":"id";
        if(endpoint.contains("merchant/orders") || endpoint.contains("courier/deliveries")) {
          String database=endpoint.contains("merchant/orders")?"parcel_pricing_db":"dispatch_courier_db";
          String user=endpoint.contains("merchant/orders")?"parcel_pricing_user":"dispatch_user";
          String password=endpoint.contains("merchant/orders")?"parcel-pricing-only":"dispatch-only";
          String query=endpoint.contains("merchant/orders")?"SELECT id::text FROM parcel.parcel WHERE merchant_id='"+merchantSubject+"'"
              :"SELECT parcel_id::text FROM dispatch.delivery_assignment WHERE courier_profile_id='"+courierId+"' AND courier_user_id='"+courierUserId+"'";
          var owned=new java.util.HashSet<String>();
          try(var connection=db(database,user,password);var statement=connection.createStatement();var ids=statement.executeQuery(query)) {
            while(ids.next())owned.add(ids.getString(1));
          }
          for(var item:body)assertThat(owned).contains(item.path(idField).asText());
        }
        for(String forbidden:List.of(customerToken,courierToken,merchantToken)) {
          if(endpoint.contains("admin/") || endpoint.contains("dispatch/"))
            assertThat(call("GET",endpoint,forbidden,null).statusCode()).isEqualTo(403);
        }
        // Whole lists are findings, not pagination failures. Bounds identify follow-up priorities.
      }
      var own=JSON.readTree(call("GET","/api/v1/merchant/orders",merchantToken,null).body());
      assertThat(own.size()).isEqualTo(50); // Existing limit-only API: default 50, maximum 100, no page traversal.
      var deliveries=JSON.readTree(call("GET","/api/v1/courier/deliveries",courierToken,null).body());
      assertThat(deliveries.size()).isEqualTo(volume);
      recordListPlans(volume);
    }
  }
  private static void recordListPlans(int volume) throws Exception {
    StringBuilder output=new StringBuilder("\nSeed rows="+volume+"; repository query plans in each owning database.\n");
    var plans=List.of(
        new String[]{"identity_db","identity_user","identity-only", """
          SELECT u.id,u.keycloak_subject,u.email,u.display_name,u.phone,u.account_status,coalesce(m.status,'') AS merchant_status
          FROM identity.app_user u LEFT JOIN identity.merchant_application m ON m.app_user_id=u.id WHERE 1=1 ORDER BY u.created_at DESC
          """},
        new String[]{"identity_db","identity_user","identity-only", """
          SELECT cp.id AS profile_id,u.id AS user_id,u.email,u.display_name,u.phone,u.account_status,cp.is_active,
          cp.assigned_zone,cp.vehicle_type,cp.created_at,cp.updated_at FROM identity.app_user u
          INNER JOIN identity.courier_profile cp ON cp.app_user_id=u.id WHERE 1=1 ORDER BY cp.created_at DESC
          """},
        new String[]{"identity_db","identity_user","identity-only", """
          SELECT id,app_user_id,business_name,business_reg_no,contact_phone,status,rejection_reason,suspension_reason,created_at,reviewed_at
          FROM identity.merchant_application ORDER BY created_at
          """},
        new String[]{"dispatch_courier_db","dispatch_user","dispatch-only", """
          SELECT a.parcel_id,a.courier_profile_id,a.assigned_at,p.id AS proof_id,p.completion_state
          FROM dispatch.delivery_assignment a LEFT JOIN dispatch.proof_of_delivery p ON p.parcel_id=a.parcel_id
          WHERE a.courier_profile_id='%s' AND a.courier_user_id='%s' ORDER BY a.assigned_at DESC,a.parcel_id
          """.formatted(courierId,courierUserId)},
        new String[]{"dispatch_courier_db","dispatch_user","dispatch-only",
          "SELECT courier_profile_id,COUNT(*) AS assignment_count FROM dispatch.delivery_assignment GROUP BY courier_profile_id"},
        new String[]{"parcel_pricing_db","parcel_pricing_user","parcel-pricing-only", """
          SELECT id,tracking_ref,merchant_id,external_order_ref,sender_name,sender_phone,sender_address,sender_city,
          recipient_name,recipient_phone,recipient_address,recipient_city,weight_kg,size,category,description,destination_type,
          is_express,notes,fee,currency,estimated_days,status,request_hash,created_at,updated_at
          FROM parcel.parcel WHERE merchant_id='%s' ORDER BY created_at DESC LIMIT 50
          """.formatted(merchantSubject)},
        new String[]{"tracking_reporting_db","tracking_user","tracking-only",
          "SELECT * FROM tracking.tracking_event WHERE tracking_ref='PERF-HISTORY' ORDER BY occurred_at ASC"},
        new String[]{"parcel_pricing_db","parcel_pricing_user","parcel-pricing-only",
          "SELECT id,code,name,description,active FROM pricing.delivery_zone WHERE active=true ORDER BY name ASC"});
    for(var plan:plans)try(var connection=db(plan[0],plan[1],plan[2]);var statement=connection.createStatement()) {
      statement.setQueryTimeout(10);output.append(plan[0]).append('\n').append(plan[3]);
      try(var rows=statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS) "+plan[3])) {
        while(rows.next())output.append(rows.getString(1)).append('\n');
      }
    }
    Files.writeString(MODULE.resolve("target/performance-growth-plans.txt"),output,java.nio.file.StandardOpenOption.CREATE,
        java.nio.file.StandardOpenOption.APPEND);
  }
  private static void isolation() throws Exception {
    merchantToken=tokenForClient("perf-merchant",merchantSecret);
    try(var receiver=new okhttp3.mockwebserver.MockWebServer()) {
      receiver.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
        @Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest r) {
          return new okhttp3.mockwebserver.MockResponse().setResponseCode(503).setHeadersDelay(200,TimeUnit.MILLISECONDS);
        }
      });
      receiver.start(java.net.InetAddress.getLoopbackAddress(),0);
      assertThat(call("POST","/api/v1/merchant/webhooks",merchantToken,
          "{\"url\":\"http://127.0.0.1:"+receiver.getPort()+"/events\"}").statusCode()).isEqualTo(201);
      assertThat(call("POST","/api/v1/merchant/orders",merchantToken,
          BODY.substring(0,BODY.lastIndexOf('}'))+",\"externalOrderRef\":\"SYNTHETIC-ISOLATION\"}").statusCode()).isEqualTo(201);
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(()->receiver.getRequestCount()>0);
      ramp("failing-webhook-tracking",i->read("/api/v1/tracking/PERF-1",null,200));
      ramp("failing-webhook-booking",i->booking(UUID.randomUUID().toString(),true));
      assertThat(call("DELETE","/api/v1/merchant/webhooks",merchantToken,null).statusCode()).isIn(200,204);
    }
    try(var slow=new okhttp3.mockwebserver.MockWebServer()) {
      slow.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
        @Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest r) {
          return new okhttp3.mockwebserver.MockResponse().setBody("{}").setHeadersDelay(2,TimeUnit.SECONDS);
        }
      });
      slow.start(java.net.InetAddress.getLoopbackAddress(),0);
      stop(gateway);
      gateway=launch("api-gateway",18080,java.util.Map.ofEntries(
          java.util.Map.entry("GATEWAY_PORT","18080"),java.util.Map.entry("JWT_ISSUER_URI",issuer),
          java.util.Map.entry("JWT_JWKS_URI",issuer+"/protocol/openid-connect/certs"),java.util.Map.entry("JWT_AUDIENCE","zentro-api"),
          java.util.Map.entry("S1_URI","http://127.0.0.1:18081"),java.util.Map.entry("S2_URI","http://127.0.0.1:"+slow.getPort()),
          java.util.Map.entry("S5_URI","http://127.0.0.1:18085"),java.util.Map.entry("GATEWAY_RESPONSE_TIMEOUT","200ms")));
      ramp("slow-upstream-504",i->request("POST","/api/v1/parcels/estimate",customerToken,"{}",504,null));
      // Fixed background requests keep the slow route active during the independent-route measurements.
      try(var background=Executors.newFixedThreadPool(5)) {
        var futures=new ArrayList<java.util.concurrent.Future<Sample>>();
        for(int i=0;i<50;i++)futures.add(background.submit(()->request("POST","/api/v1/parcels/estimate",customerToken,"{}",504,null)));
        ramp("other-route-responsive",i->read("/api/v1/tracking/PERF-1",null,200));
        for(var future:futures)assertThat(future.get(15,TimeUnit.SECONDS).valid()).isTrue();
      }
    }
  }
  private static void consumerLag() throws Exception {
    StringBuilder evidence=new StringBuilder();
    for(String group:List.of("tracking-reporting-service","notification-integration-service","merchant-webhooks")) {
      String[] last={""};
      org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofSeconds(1)).until(()->{
        var result=KAFKA.execInContainer("timeout","10","kafka-consumer-groups","--bootstrap-server","localhost:9092",
            "--group",group,"--describe");
        last[0]=result.getStdout();
        if(result.getExitCode()!=0)return false;
        long lag=0;int partitions=0;
        for(String line:last[0].split("\\R")) {
          String[] columns=line.trim().split("\\s+");
          if(columns.length>=6 && columns[0].equals(group) && columns[5].matches("[0-9]+")) {
            partitions++;lag+=Long.parseLong(columns[5]);
          }
        }
        return partitions>0 && lag==0;
      });
      evidence.append(group).append(": lag drained to zero\n").append(last[0]).append('\n');
    }
    Files.writeString(MODULE.resolve("target/performance-lag.txt"),evidence);
  }
  private static void capturePlans() throws Exception {
    String[] queries={
        "SELECT * FROM tracking.tracking_parcel WHERE customer_subject='"+customerId+"' ORDER BY created_at DESC OFFSET 0 LIMIT 20",
        "SELECT * FROM tracking.tracking_parcel WHERE customer_subject='"+customerId+"' ORDER BY created_at DESC OFFSET 5000 LIMIT 20",
        "SELECT count(*) FROM tracking.tracking_parcel WHERE customer_subject='"+customerId+"'",
        "SELECT * FROM tracking.tracking_event WHERE tracking_ref IN ('PERF-1','PERF-2') ORDER BY occurred_at ASC"};
    StringBuilder output=new StringBuilder("S4 owning-database plans; same predicates/order as repository, full entity projection.\n");
    try(var connection=db("tracking_reporting_db","tracking_user","tracking-only");var statement=connection.createStatement()) {
      statement.setQueryTimeout(10);
      for(String query:queries) {output.append(query).append('\n');try(var rows=statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS) "+query)) {
        while(rows.next())output.append(rows.getString(1)).append('\n');
      }}
    }
    Files.writeString(MODULE.resolve("target/performance-plans.txt"),output);
  }
  private static double[] dbSignals(String endpoint) throws Exception {
    double[] result={0,0};
    List<String[]> databases=endpoint.contains("tracking/")
        ? List.<String[]>of(new String[]{"tracking_reporting_db","tracking_user","tracking-only"})
        :endpoint.contains("/zones")?List.<String[]>of(new String[]{"parcel_pricing_db","parcel_pricing_user","parcel-pricing-only"})
        :endpoint.contains("merchant/orders")
        ? List.<String[]>of(new String[]{"parcel_pricing_db","parcel_pricing_user","parcel-pricing-only"},
            new String[]{"identity_db","identity_user","identity-only"})
        : endpoint.contains("dispatch/") || endpoint.contains("courier/deliveries")
        ? List.<String[]>of(new String[]{"dispatch_courier_db","dispatch_user","dispatch-only"},
            new String[]{"identity_db","identity_user","identity-only"})
        : List.<String[]>of(new String[]{"identity_db","identity_user","identity-only"});
    for(var database:databases)try(var connection=db(database[0],database[1],database[2]);var statement=connection.createStatement();
        var rows=statement.executeQuery("""
            SELECT coalesce(sum(total_exec_time),0),coalesce(sum(calls),0) FROM pg_stat_statements
            WHERE userid=(SELECT oid FROM pg_roles WHERE rolname=current_user)
            AND dbid=(SELECT oid FROM pg_database WHERE datname=current_database())
            AND query NOT ILIKE '%pg_stat_statements%' AND query ILIKE 'select%'
            """)) {
      rows.next();result[0]+=rows.getDouble(1);result[1]+=rows.getDouble(2);
    }
    return result;
  }
  private static void seedLists(int from,int to) throws Exception {
    if(from==1) {
      // A future-sorted foreign row exposes a missing owner predicate even on S2's bounded first 50.
      execute("parcel_pricing_db","parcel_pricing_user","parcel-pricing-only", """
          INSERT INTO parcel.parcel(tracking_ref,customer_subject,merchant_id,external_order_ref,sender_name,sender_phone,
            sender_address,recipient_name,recipient_phone,recipient_address,weight_kg,category,fee,currency,estimated_days,created_at)
          VALUES('FOREIGN-SYNTH','SYNTHETIC-OTHER','SYNTHETIC-OTHER','SYNTHETIC-OTHER','SYNTHETIC','0000000000',
            'SYNTHETIC FIXTURE','SYNTHETIC','0000000000','SYNTHETIC FIXTURE',1,'GENERAL',952,'LKR',3,'2027-01-01T00:00:00Z')
          """);
      execute("dispatch_courier_db","dispatch_user","dispatch-only", """
          INSERT INTO dispatch.delivery_assignment(parcel_id,courier_profile_id,courier_user_id,assigned_by_subject,assigned_at)
          VALUES(gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),'SYNTHETIC-OTHER','2027-01-01T00:00:00Z')
          """);
    }
    if(from==1)execute("tracking_reporting_db","tracking_user","tracking-only", """
        INSERT INTO tracking.tracking_parcel(tracking_ref,customer_subject,sender_name,sender_phone,sender_address,
          recipient_name,recipient_phone,recipient_address)
        VALUES('PERF-HISTORY','SYNTHETIC-HISTORY','SYNTHETIC','0000000000','SYNTHETIC FIXTURE',
          'SYNTHETIC','0000000000','SYNTHETIC FIXTURE')
        """);
    execute("tracking_reporting_db","tracking_user","tracking-only", """
        INSERT INTO tracking.tracking_event(tracking_ref,status,title,occurred_at)
        SELECT 'PERF-HISTORY','CREATED','SYNTHETIC','2026-01-01T00:00:00Z'::timestamptz+n*interval '1 second'
        FROM generate_series(%d,%d)n
        """.formatted(from,to));
    execute("parcel_pricing_db","parcel_pricing_user","parcel-pricing-only", """
        INSERT INTO pricing.delivery_zone(code,name) SELECT 'SYNTHETIC-ZONE-'||n,'SYNTHETIC '||n
        FROM generate_series(%d,%d)n
        """.formatted(from,to));
    execute("identity_db","identity_user","identity-only", """
        INSERT INTO identity.app_user(keycloak_subject,display_name)
        SELECT 'SYNTHETIC-GROWTH-'||n,'SYNTHETIC '||n FROM generate_series(%d,%d)n;
        INSERT INTO identity.courier_profile(app_user_id)
        SELECT id FROM identity.app_user WHERE keycloak_subject LIKE 'SYNTHETIC-GROWTH-%%'
        ON CONFLICT(app_user_id) DO NOTHING;
        INSERT INTO identity.merchant_application(app_user_id,business_name,contact_phone)
        SELECT id,'SYNTHETIC','0000000000' FROM identity.app_user WHERE keycloak_subject LIKE 'SYNTHETIC-GROWTH-%%'
        ON CONFLICT(app_user_id) DO NOTHING;
        """.formatted(from,to));
    execute("dispatch_courier_db","dispatch_user","dispatch-only", """
        INSERT INTO dispatch.delivery_assignment(parcel_id,courier_profile_id,courier_user_id,assigned_by_subject)
        SELECT md5('SYNTHETIC-'||n)::uuid,'%s'::uuid,'%s'::uuid,'SYNTHETIC' FROM generate_series(%d,%d)n
        """.formatted(courierId,courierUserId,from,to));
    execute("parcel_pricing_db","parcel_pricing_user","parcel-pricing-only", """
        INSERT INTO parcel.parcel(tracking_ref,customer_subject,merchant_id,external_order_ref,sender_name,sender_phone,
          sender_address,recipient_name,recipient_phone,recipient_address,weight_kg,category,fee,currency,estimated_days)
        SELECT 'SYNTH-'||n,'SYNTHETIC','%s','SYNTHETIC-'||n,'SYNTHETIC','0000000000','SYNTHETIC FIXTURE',
          'SYNTHETIC','0000000000','SYNTHETIC FIXTURE',1,'GENERAL',952,'LKR',3 FROM generate_series(%d,%d)n
        """.formatted(merchantSubject,from,to));
    execute("identity_db","identity_user","identity-only","ANALYZE identity.app_user; ANALYZE identity.courier_profile; ANALYZE identity.merchant_application");
    execute("dispatch_courier_db","dispatch_user","dispatch-only","ANALYZE dispatch.delivery_assignment");
    execute("parcel_pricing_db","parcel_pricing_user","parcel-pricing-only","ANALYZE parcel.parcel; ANALYZE pricing.delivery_zone");
    execute("tracking_reporting_db","tracking_user","tracking-only","ANALYZE tracking.tracking_event");
  }
}
