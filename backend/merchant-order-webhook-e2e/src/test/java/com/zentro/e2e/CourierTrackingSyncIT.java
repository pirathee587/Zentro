package com.zentro.e2e;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.nio.file.*;
import java.time.Duration;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/** Real S2/S3/S4, gateway, Kafka and Keycloak. No shared networks, credentials or databases. */
@EnabledIfSystemProperty(named="zentro.e2e.statusSync", matches="true")
class CourierTrackingSyncIT extends DisposableGatewayStack {
  static final Network NETWORK = Network.newNetwork();
  static final Map<String,GenericContainer<?>> APPS = new LinkedHashMap<>();
  static String courierToken, edge;
  static String customerPassword="isolated-customer-password", courierPassword="isolated-courier-password";
  static void startIsolatedStack() throws Exception {
    POSTGRES.withNetwork(NETWORK).withNetworkAliases("postgres");
    KAFKA.withNetwork(NETWORK).withNetworkAliases("kafka");
    KEYCLOAK.withNetwork(NETWORK).withNetworkAliases("keycloak").withEnv("KC_HOSTNAME", "http://localhost:28180");
    KEYCLOAK.setPortBindings(List.of("127.0.0.1:28180:8080"));
    org.testcontainers.lifecycle.Startables.deepStart(java.util.stream.Stream.of(POSTGRES,KAFKA)).join();
    initializeDatabases();
    createDatabase("dispatch_user","dispatch-only","dispatch_courier_db");
    createDatabase("tracking_user","tracking-only","tracking_reporting_db");
    startKeycloak(); issuer="http://localhost:28180/realms/zentro";
    createTestClient();
    createServiceClient("zentro-parcel-service",PARCEL_SERVICE_SECRET,"SERVICE");
    createServiceClient("zentro-dispatch-service",DISPATCH_SERVICE_SECRET,"SERVICE");
    createServiceClient("zentro-tracking-service",TRACKING_SERVICE_SECRET,"SERVICE");
    var dispatchClient=send("GET",keycloak()+"/admin/realms/zentro/clients?clientId=zentro-dispatch-service",adminToken(),null,200).get(0).path("id").asText();
    send("POST",keycloak()+"/admin/realms/zentro/clients/"+dispatchClient+"/protocol-mappers/models",adminToken(),JSON.writeValueAsString(Map.of("name","fixture pod permissions","protocol","openid-connect","protocolMapper","oidc-hardcoded-claim-mapper","config",Map.of("claim.name","pod_permissions","claim.value","[\"pod:read\",\"pod:update\"]","jsonType.label","JSON","access.token.claim","true"))),201);
    var realm=send("GET",keycloak()+"/admin/realms/zentro",adminToken(),null,200).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)realm).put("verifyEmail",false).put("loginTheme","keycloak");
    send("PUT",keycloak()+"/admin/realms/zentro",adminToken(),JSON.writeValueAsString(realm),204);
    var browser=send("GET",keycloak()+"/admin/realms/zentro/clients?clientId=zentro-web",adminToken(),null,200).get(0).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode)browser).put("directAccessGrantsEnabled",true)
        .set("redirectUris",JSON.valueToTree(List.of("http://localhost:28173/*")));
    ((com.fasterxml.jackson.databind.node.ObjectNode)browser).set("webOrigins",JSON.valueToTree(List.of("http://localhost:28173")));
    send("PUT",keycloak()+"/admin/realms/zentro/clients/"+browser.path("id").asText(),adminToken(),JSON.writeValueAsString(browser),204);
    customerId=createUser("sync-customer",customerPassword,"CUSTOMER");
    createUser("sync-courier",courierPassword,"COURIER");
    customerToken=userToken("sync-customer",customerPassword);courierToken=userToken("sync-courier",courierPassword);
    dispatcherToken=tokenForClient(DISPATCHER_CLIENT,DISPATCHER_SECRET);
    app("identity-partner-service",8081,"identity_db","identity_user","identity-only",Map.of("GRPC_SERVER_ENABLED","true"));
    app("parcel-pricing-service",8082,"parcel_pricing_db","parcel_pricing_user","parcel-pricing-only",Map.of("OUTBOX_PUBLISHER_ENABLED","true","ZENTRO_ASSIGNMENT_ELIGIBLE_STATUSES","CREATED","DISPATCH_SERVICE_BASE_URL","http://dispatch-courier-service:8083","SERVICE_CLIENT_ID","zentro-parcel-service","SERVICE_CLIENT_SECRET",PARCEL_SERVICE_SECRET));
    app("dispatch-courier-service",8083,"dispatch_courier_db","dispatch_user","dispatch-only",Map.of("POD_WORKER_ENABLED","true","OUTBOX_ENABLED","false","POD_TOKEN_URI","http://keycloak:8080/realms/zentro/protocol/openid-connect/token","POD_CLIENT_ID","zentro-dispatch-service","POD_CLIENT_SECRET",DISPATCH_SERVICE_SECRET));
    app("tracking-reporting-service",8084,"tracking_reporting_db","tracking_user","tracking-only",Map.of("KAFKA_CONSUMER_ENABLED","true","SERVICE_CLIENT_ID","zentro-tracking-service","SERVICE_CLIENT_SECRET",TRACKING_SERVICE_SECRET));
    app("api-gateway",8080,null,null,null,Map.of("S1_URI","http://identity-partner-service:8081","S2_URI","http://parcel-pricing-service:8082","S3_URI","http://dispatch-courier-service:8083","S4_URI","http://tracking-reporting-service:8084","CORS_ALLOWED_ORIGINS","http://localhost:28173"));
    edge=origin("api-gateway");
  }
  static void app(String module,int port,String db,String user,String password,Map<String,String> extra) {
    var env=new HashMap<String,String>();
    env.putAll(Map.of("JWT_ISSUER_URI",issuer,"JWT_JWKS_URI","http://keycloak:8080/realms/zentro/protocol/openid-connect/certs","SPRING_CONFIG_IMPORT","","SERVER_PORT",Integer.toString(port),"GATEWAY_PORT",Integer.toString(port),"JAVA_TOOL_OPTIONS","-XX:ActiveProcessorCount=2","KAFKA_BOOTSTRAP_SERVERS","kafka:9092","SPRING_KAFKA_BOOTSTRAP_SERVERS","kafka:9092"));
    env.putAll(Map.of("IDENTITY_GRPC_HOST","identity-partner-service","PARCEL_GRPC_HOST","parcel-pricing-service","DISPATCH_GRPC_HOST","dispatch-courier-service","SERVICE_TOKEN_URI","http://keycloak:8080/realms/zentro/protocol/openid-connect/token"));
    if(db!=null)env.putAll(Map.of("DB_URL","jdbc:postgresql://postgres:5432/"+db,"DB_USER",user,"DB_PASSWORD",password,"SPRING_FLYWAY_ENABLED","true"));
    env.put("PARCEL_BASE_URL", "http://parcel-pricing-service:8082");
    env.putAll(extra);
    var c=new GenericContainer<>("zentro-"+module+":latest").withNetwork(NETWORK).withNetworkAliases(module).withEnv(env).withExposedPorts(port)
      .withCopyFileToContainer(MountableFile.forHostPath(MODULE.getParent().resolve(module+"/target/"+module+"-0.1.0-SNAPSHOT.jar")),"/app/app.jar")
      .waitingFor(Wait.forHttp("/actuator/health/liveness").forPort(port).forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));
    APPS.put(module,c);c.start();
  }
  static String origin(String module){var c=APPS.get(module);return "http://localhost:"+c.getMappedPort(c.getExposedPorts().get(0));}
  static String createUser(String name,String password,String role)throws Exception {
    send("POST",keycloak()+"/admin/realms/zentro/users",adminToken(),JSON.writeValueAsString(Map.of("username",name,"firstName","Isolated","lastName",role,"email",name+"@example.test","enabled",true,"emailVerified",true,"credentials",List.of(Map.of("type","password","value",password,"temporary",false)))),201);
    String id=send("GET",keycloak()+"/admin/realms/zentro/users?username="+name,adminToken(),null,200).get(0).path("id").asText();
    simulateRoleAssignment(adminToken(),id,role);return id;
  }
  static String userToken(String user,String password)throws Exception{
    var response=sendRaw("POST",issuer+"/protocol/openid-connect/token",null,"grant_type=password&client_id=zentro-web&username="+encode(user+"@example.test")+"&password="+encode(password),"application/x-www-form-urlencoded");
    assertThat(response.statusCode()).as("Isolated user token response: "+response.body().replaceAll("\"access_token\":\"[^\"]*\"", "\"access_token\":\"redacted\"" )).isEqualTo(200);
    return JSON.readTree(response.body()).path("access_token").asText();
  }
  static com.fasterxml.jackson.databind.JsonNode call(String method,String path,String token,String body,int status)throws Exception{return send(method,edge+path,token,body,status);}
  static com.fasterxml.jackson.databind.JsonNode query(String query,String token)throws Exception {
    var result=call("POST","/graphql",token,JSON.writeValueAsString(Map.of("query",query)),200);
    assertThat(result.has("errors")).as(result.toString()).isFalse();return result.path("data");
  }
  static com.fasterxml.jackson.databind.JsonNode bookAndAssign(String courierId)throws Exception {
    var booking=call("POST","/api/v1/parcels",customerToken,orderBody(),201);
    call("POST","/api/v1/dispatch/assignments",dispatcherToken,JSON.writeValueAsString(Map.of("parcelId",booking.path("id").asText(),"courierId",courierId)),201);return booking;
  }
  @Test void courierActionsReachSameCustomerParcelAndSurviveFreshReads()throws Exception {
    var profile=call("POST","/api/v1/courier/profile/provision",courierToken,"{}",200);
    String courierId=profile.path("id").asText();
    var booking=bookAndAssign(courierId);String parcel=booking.path("id").asText(),ref=booking.path("trackingRef").asText();
    call("POST","/api/v1/courier/deliveries/"+parcel+"/accept",courierToken,"{}",200);
    var assignment=call("GET","/api/v1/courier/deliveries/"+parcel,courierToken,null,200);
    assertThat(assignment.path("trackingRef").asText()).isEqualTo(ref);
    String ownerQuery="{customerDelivery(trackingRef:\""+ref+"\"){id rawStatus events{id status} courier{name assignmentStatus}}}";
    var accepted=query(ownerQuery,customerToken).path("customerDelivery");
    assertThat(accepted.path("id").asText()).isEqualTo(parcel);
    assertThat(accepted.path("courier").path("assignmentStatus").asText()).isEqualTo("ACCEPTED");
    List<String> expected=new ArrayList<>();
    for(String state:List.of("PICKED_UP","IN_TRANSIT","OUT_FOR_DELIVERY")){
      String body="{\"status\":\""+state+"\"}";
      call("PATCH","/api/v1/courier/deliveries/"+parcel+"/status",courierToken,body,200);
      var retry=call("PATCH","/api/v1/courier/deliveries/"+parcel+"/status",courierToken,body,200);
      assertThat(retry.path("changed").asBoolean()).isFalse();expected.add(state);
      var tracking=call("GET","/api/v1/tracking/"+ref,null,null,200);
      assertThat(tracking.path("rawStatus").asText()).isEqualTo(state);
      assertThat(tracking.path("events").findValuesAsText("status")).containsExactlyElementsOf(expected);
      assertThat(query(ownerQuery,customerToken).path("customerDelivery").path("rawStatus").asText()).isEqualTo(state);
    }
    String proof="{\"recipientName\":\"Isolated recipient\",\"proofReference\":\"fixture-proof\",\"notes\":null}";
    call("POST","/api/v1/courier/deliveries/"+parcel+"/proof",courierToken,proof,201);
    call("POST","/api/v1/courier/deliveries/"+parcel+"/proof",courierToken,proof,200);
    await().atMost(Duration.ofSeconds(45)).untilAsserted(()->assertThat(call("GET","/api/v1/tracking/"+ref,null,null,200).path("rawStatus").asText()).isEqualTo("DELIVERED"));
    await().atMost(Duration.ofSeconds(45)).untilAsserted(()->{
      try(var c=DriverManager.getConnection(jdbcUrl("tracking_reporting_db"),"tracking_user","tracking-only");var stmt=c.prepareStatement("SELECT count(*) FROM tracking.tracking_event WHERE parcel_id=?")){stmt.setObject(1,UUID.fromString(parcel));try(var rows=stmt.executeQuery()){rows.next();assertThat(rows.getLong(1)).isEqualTo(5);}}
    });
    var finalRead=call("GET","/api/v1/tracking/"+ref,null,null,200);
    assertThat(finalRead.path("events").size()).isEqualTo(4);
    assertThat(finalRead.path("deliveredAt").asText()).isNotBlank();
    assertThat(query(ownerQuery,customerToken).path("customerDelivery").path("events").size()).isEqualTo(4);
    Files.writeString(MODULE.resolve("target/status-sync-api-result.json"),finalRead.toPrettyString());
    if(Boolean.getBoolean("zentro.e2e.statusSyncBrowser")){
      var browserBooking=bookAndAssign(courierId);
      Files.writeString(MODULE.resolve("target/status-sync-browser.json"),JSON.writeValueAsString(Map.of("gateway",edge,"keycloak","http://localhost:28180","parcelId",browserBooking.path("id").asText(),"trackingRef",browserBooking.path("trackingRef").asText())));
      // A file handshake keeps only this isolated fixture alive for the browser test.
      await().atMost(Duration.ofMinutes(20)).pollInterval(Duration.ofSeconds(2)).until(()->Files.exists(MODULE.resolve("target/status-sync-browser-complete")));
      assertThat(call("GET","/api/v1/tracking/"+browserBooking.path("trackingRef").asText(),null,null,200).path("rawStatus").asText()).isEqualTo("DELIVERED");
    }
  }
  @AfterAll static void closeApps()throws Exception{
    var entries=new ArrayList<>(APPS.entrySet());Collections.reverse(entries);
    for(var entry:entries){Files.writeString(MODULE.resolve("target/status-sync-"+entry.getKey()+".log"),entry.getValue().getLogs());entry.getValue().stop();}
    NETWORK.close();
  }
}
