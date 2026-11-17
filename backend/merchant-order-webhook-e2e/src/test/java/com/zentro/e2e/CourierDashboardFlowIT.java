package com.zentro.e2e;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.time.Duration;
import java.util.*;
import java.sql.DriverManager;
import java.nio.file.Files;
import org.junit.jupiter.api.*;

/** Actual HTTP services, Kafka and owning databases. Never targets developer/shared infrastructure. */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="zentro.e2e.courierDashboard", matches="true")
class CourierDashboardFlowIT extends DisposableGatewayStack {
  static String courier,foreign;
  static final StringBuilder evidence=new StringBuilder("# Isolated courier HTTP flow\n\n");
  static void startDashboardStack() throws Exception {
    if (CourierLocalIntegrationStack.enabled()) { CourierLocalIntegrationStack.start(); return; }
    for(int port:new int[]{18080,18081,18082,18083,18084})try(var socket=new java.net.ServerSocket(port,1,java.net.InetAddress.getLoopbackAddress())){}
    org.testcontainers.lifecycle.Startables.deepStart(java.util.stream.Stream.of(POSTGRES,KAFKA)).join();
    initializeDatabases();startKeycloak();issuer=keycloak()+"/realms/"+REALM;
    createTestClient();dispatcherToken=tokenForClient(DISPATCHER_CLIENT,DISPATCHER_SECRET);customerToken=tokenForClient(CUSTOMER_CLIENT,CUSTOMER_SECRET);
    s1=launch("identity-partner-service",18081,Map.of("DB_URL",jdbcUrl("identity_db"),"DB_USER","identity_user","DB_PASSWORD","identity-only","JWT_ISSUER_URI",issuer,"JWT_JWKS_URI",issuer+"/protocol/openid-connect/certs","JWT_AUDIENCE","zentro-api","GRPC_SERVER_ENABLED","false","JAVA_TOOL_OPTIONS","-XX:ActiveProcessorCount=2"));
    configureDashboardFixture();
  }
  static void configureDashboardFixture() throws Exception {
    try(var a=new java.net.ServerSocket(18083,1,java.net.InetAddress.getLoopbackAddress());var b=new java.net.ServerSocket(18084,1,java.net.InetAddress.getLoopbackAddress())) {}
    createDatabase("dispatch_user","dispatch-only","dispatch_courier_db");
    createDatabase("tracking_user","tracking-only","tracking_reporting_db");
    // These processes belong exclusively to this disposable harness, not the developer's Docker stack.
    stop(gateway);stop(s4);stop(s3);stop(s2);
    var common=new HashMap<String,String>();common.put("JWT_ISSUER_URI",issuer);common.put("JWT_JWKS_URI",issuer+"/protocol/openid-connect/certs");common.put("JWT_AUDIENCE","zentro-api");common.put("KAFKA_BOOTSTRAP_SERVERS",KAFKA.getBootstrapServers());common.put("JAVA_TOOL_OPTIONS","-XX:ActiveProcessorCount=2");
    var parcel=new HashMap<>(common);parcel.putAll(Map.of("DB_URL",jdbcUrl("parcel_pricing_db"),"DB_USER","parcel_pricing_user","DB_PASSWORD","parcel-pricing-only","OUTBOX_PUBLISHER_ENABLED","true","ZENTRO_ASSIGNMENT_ELIGIBLE_STATUSES","PICKED_UP","ZENTRO_ASSIGNMENT_DISPATCH_BASE_URL","http://127.0.0.1:18083"));
    s2=launch("parcel-pricing-service",18082,parcel);
    var dispatch=new HashMap<>(common);dispatch.putAll(Map.of("DB_URL",jdbcUrl("dispatch_courier_db"),"DB_USER","dispatch_user","DB_PASSWORD","dispatch-only","IDENTITY_BASE_URL","http://127.0.0.1:18081","PARCEL_BASE_URL","http://127.0.0.1:18082","POD_WORKER_ENABLED","false","OUTBOX_ENABLED","true","SPRING_KAFKA_BOOTSTRAP_SERVERS",KAFKA.getBootstrapServers()));
    s3=launch("dispatch-courier-service",18083,dispatch);
    var tracking=new HashMap<>(common);tracking.put("KAFKA_CONSUMER_ENABLED","true");tracking.putAll(Map.of("DB_URL",jdbcUrl("tracking_reporting_db"),"DB_USER","tracking_user","DB_PASSWORD","tracking-only","ZENTRO_DASHBOARD_DISPATCH_BASE_URL","http://127.0.0.1:18083"));
    s4=launch("tracking-reporting-service",18084,tracking);
    gateway=launch("api-gateway",18080,Map.of("S1_URI","http://127.0.0.1:18081","S2_URI","http://127.0.0.1:18082","S3_URI","http://127.0.0.1:18083","S4_URI","http://127.0.0.1:18084","JAVA_TOOL_OPTIONS","-XX:ActiveProcessorCount=2","JWT_ISSUER_URI",issuer,"JWT_JWKS_URI",issuer+"/protocol/openid-connect/certs","JWT_AUDIENCE","zentro-api"));
    configureAuthenticationFixture();
  }
  static void configureAuthenticationFixture() throws Exception {
    var clients=send("GET",keycloak()+"/admin/realms/zentro/clients?clientId="+DISPATCHER_CLIENT,adminToken(),null,200);
    String fixtureClient=clients.get(0).path("id").asText();
    send("POST",keycloak()+"/admin/realms/zentro/clients/"+fixtureClient+"/protocol-mappers/models",adminToken(),
      JSON.writeValueAsString(Map.of("name","Courier dashboard fixture permissions","protocol","openid-connect","protocolMapper","oidc-hardcoded-claim-mapper","config",Map.of("claim.name","permissions","claim.value","[\"DISPATCH_VIEW\",\"DISPATCH_ASSIGN\"]","jsonType.label","JSON","access.token.claim","true"))),201);
    dispatcherToken=tokenForClient(DISPATCHER_CLIENT,DISPATCHER_SECRET);
    courier=BackendAcceptanceStack.createIsolatedRoleClient("courier-dashboard-owner",UUID.randomUUID().toString(),"COURIER");foreign=BackendAcceptanceStack.createIsolatedRoleClient("courier-dashboard-foreign",UUID.randomUUID().toString(),"COURIER");
  }
  static com.fasterxml.jackson.databind.JsonNode request(String method,String origin,String path,String token,String body,int expected) throws Exception {
    var response=sendRaw(method,origin+path,token,body);
    evidence.append(method).append(" ").append(path).append(" -> ").append(response.statusCode()).append("\n```json\n").append(response.body()).append("\n```\n");
    Files.writeString(MODULE.resolve("target/courier-dashboard-http-evidence.md"),evidence);
    assertThat(response.statusCode()).as(method+" "+path).isEqualTo(expected);
    return response.body().isBlank()?JSON.createObjectNode():JSON.readTree(response.body());
  }
  static long count(String database,String user,String password,String sql) throws Exception {
    try(var db=DriverManager.getConnection(jdbcUrl(database),user,password);var stmt=db.createStatement();var rs=stmt.executeQuery(sql)){rs.next();return rs.getLong(1);}
  }
  @AfterAll static void stopLocalIntegration() {
    if (CourierLocalIntegrationStack.enabled()) CourierLocalIntegrationStack.stop();
  }
  static String identitySnapshot() throws Exception {
    try(var db=DriverManager.getConnection(jdbcUrl("identity_db"),"identity_user","identity-only");var stmt=db.createStatement();
        var rs=stmt.executeQuery("SELECT (SELECT jsonb_agg(to_jsonb(u) || jsonb_build_object('row_version', u.xmin::text) ORDER BY u.id)::text FROM identity.app_user u) || (SELECT jsonb_agg(to_jsonb(p) || jsonb_build_object('row_version', p.xmin::text) ORDER BY p.id)::text FROM identity.courier_profile p)")) {
      rs.next(); return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(rs.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
  }
  @Test void fullAssignmentProjectionAndCourierGraphQlFlow() throws Exception {
    String edge=CourierLocalIntegrationStack.origin("api-gateway",18080),s2url=CourierLocalIntegrationStack.origin("parcel-pricing-service",18082),s3url=CourierLocalIntegrationStack.origin("dispatch-courier-service",18083);
    var profile=request("POST",edge,"/api/v1/courier/profile/provision",courier,"{}",200);
    request("POST",edge,"/api/v1/courier/profile/provision",foreign,"{}",200);
    String courierId=profile.path("id").asText();assertThat(courierId).isNotBlank();
    var booked=request("POST",edge,"/api/v1/parcels",customerToken,orderBody(),201);
    String parcel=booked.path("id").asText();String ref=booked.path("trackingRef").asText();
    request("GET",s2url,"/internal/assignment-claims/candidates/"+parcel,dispatcherToken,null,409);
    request("PATCH",edge,"/api/v1/parcels/"+parcel+"/status",dispatcherToken,"{\"status\":\"PICKED_UP\"}",200);
    request("GET",s2url,"/internal/assignment-claims/candidates/"+parcel,dispatcherToken,null,200);
    String assign="{\"parcelId\":\""+parcel+"\",\"courierId\":\""+courierId+"\"}";
    request("POST",edge,"/api/v1/dispatch/assignments",courier,assign,403);
    request("POST",edge,"/api/v1/dispatch/assignments",dispatcherToken,assign,201);
    request("POST",edge,"/api/v1/dispatch/assignments",dispatcherToken,assign,409);
    assertThat(count("dispatch_courier_db","dispatch_user","dispatch-only","SELECT count(*) FROM dispatch.delivery_assignment")).isEqualTo(1);
    assertThat(count("dispatch_courier_db","dispatch_user","dispatch-only","SELECT count(*) FROM dispatch.assignment_audit")).isEqualTo(1);
    assertThat(count("dispatch_courier_db","dispatch_user","dispatch-only","SELECT count(*) FROM dispatch.assignment_operation WHERE state='COMMITTED' AND settled")).isEqualTo(1);
    assertThat(count("parcel_pricing_db","parcel_pricing_user","parcel-pricing-only","SELECT count(*) FROM parcel.assignment_claim WHERE state='CONFIRMED'")).isEqualTo(1);
    assertThat(count("parcel_pricing_db","parcel_pricing_user","parcel-pricing-only","SELECT count(*) FROM pricing.flyway_schema_history WHERE version='10' AND success")).isEqualTo(1);
    assertThat(count("dispatch_courier_db","dispatch_user","dispatch-only","SELECT count(*) FROM dispatch.flyway_schema_history WHERE version='5' AND success")).isEqualTo(1);
    await().atMost(Duration.ofSeconds(60)).untilAsserted(()->assertThat(count("tracking_reporting_db","tracking_user","tracking-only","SELECT count(*) FROM tracking.delivery_projection WHERE current_status='PICKED_UP'")).isEqualTo(1));
    String source=Files.readString(REPO.resolve("frontend/zentro-web/src/portals/courier/api/courierDashboardApi.ts"));
    var match=java.util.regex.Pattern.compile("courierTodayQuery = `([^`]+)`",java.util.regex.Pattern.DOTALL).matcher(source);assertThat(match.find()).isTrue();
    String query=JSON.writeValueAsString(Map.of("query",match.group(1),"operationName","CourierTodayDashboard","variables",Map.of("page",1,"size",10)));
    String beforeDashboard=identitySnapshot();
    var result=request("POST",edge,"/graphql",courier,query,200);assertThat(result.has("errors")).isFalse();var dashboard=result.path("data").path("courierTodayDashboard");
    assertThat(dashboard.path("counts").path("assigned").asInt()).isEqualTo(1);assertThat(dashboard.path("counts").path("remaining").asInt()).isEqualTo(1);assertThat(dashboard.path("counts").path("delivered").asInt()).isZero();assertThat(dashboard.path("counts").path("failed").asInt()).isZero();
    var card=dashboard.path("deliveries").get(0);assertThat(card.path("parcelId").asText()).isEqualTo(parcel);assertThat(card.path("trackingRef").asText()).isEqualTo(ref);assertThat(card.path("status").asText()).isEqualTo("PICKED_UP");
    var other=request("POST",edge,"/graphql",foreign,query,200);assertThat(other.has("errors")).isFalse();assertThat(other.path("data").path("courierTodayDashboard").path("deliveries").size()).isZero();
    assertThat(identitySnapshot()).as("Dashboard identity resolution must remain read-only").isEqualTo(beforeDashboard);
    request("GET",edge,"/api/v1/courier/deliveries/"+parcel,foreign,null,403);
    request("GET",edge,"/api/v1/courier/deliveries/"+parcel,courier,null,200);
    var assignments=request("GET",edge,"/api/v1/courier/deliveries",courier,null,200);
    assertThat(assignments.size()).isEqualTo(1);
    assertThat(assignments.get(0).path("parcelId").asText()).isEqualTo(parcel);
    assertThat(request("GET",edge,"/api/v1/courier/deliveries",foreign,null,200).size()).isZero();
    var noProof=request("GET",edge,"/api/v1/courier/deliveries/"+parcel+"/proof",courier,null,404);
    assertThat(noProof.path("code").asText()).isEqualTo("PROOF_NOT_FOUND");
    var proof=request("POST",edge,"/api/v1/courier/deliveries/"+parcel+"/proof",courier,
        "{\"recipientName\":\"Isolated fixture recipient\",\"notes\":null,\"proofReference\":\"isolated-fixture-proof\"}",201);
    var retrievedProof=request("GET",edge,"/api/v1/courier/deliveries/"+parcel+"/proof",courier,null,200);
    assertThat(retrievedProof.path("proofId").asText()).isEqualTo(proof.path("proofId").asText());
    assertThat(retrievedProof.path("completionState").asText()).isEqualTo("PENDING");
    var history=request("GET",edge,"/api/v1/courier/deliveries",courier,null,200);
    assertThat(history.get(0).path("proofId").asText()).isEqualTo(proof.path("proofId").asText());
    request("GET",edge,"/api/v1/courier/deliveries/"+parcel+"/proof",foreign,null,403);
    request("GET",edge,"/api/v1/courier/deliveries",null,null,401);
    request("GET",edge,"/api/v1/users/courier-profile",null,null,401);
    request("GET",edge,"/api/v1/courier/deliveries",customerToken,null,403);
    request("GET",edge,"/api/v1/users/courier-profile",customerToken,null,403);
    var denied=request("POST",edge,"/graphql",null,query,200);
    assertThat(denied.has("errors")).isTrue();
    assertThat(denied.path("data").isNull() || denied.path("data").path("courierTodayDashboard").isNull()).isTrue();
    String beforeProfile=identitySnapshot();
    long usersBeforeProfile=count("identity_db","identity_user","identity-only","SELECT count(*) FROM identity.app_user");
    long profilesBeforeProfile=count("identity_db","identity_user","identity-only","SELECT count(*) FROM identity.courier_profile");
    var savedProfile=request("GET",edge,"/api/v1/users/courier-profile",courier,null,200);
    assertThat(savedProfile.path("id").asText()).isEqualTo(courierId);
    assertThat(identitySnapshot()).as("Existing profile GET is a provisioning/upsert API, not a read-only lookup").isNotEqualTo(beforeProfile);
    assertThat(count("identity_db","identity_user","identity-only","SELECT count(*) FROM identity.app_user")).isEqualTo(usersBeforeProfile);
    assertThat(count("identity_db","identity_user","identity-only","SELECT count(*) FROM identity.courier_profile")).isEqualTo(profilesBeforeProfile);
    evidence.append("\nProfile GET: HTTP 200; identity row versions changed. NOT write-safe (existing upsert behavior), no shared database contacted.\n");
    evidence.append("\nPersistence: one assignment, one audit, settled COMMITTED operation, CONFIRMED S2 claim, S4 PICKED_UP projection.\n");
    Files.writeString(MODULE.resolve("target/courier-dashboard-http-evidence.md"),evidence);
  }
}
