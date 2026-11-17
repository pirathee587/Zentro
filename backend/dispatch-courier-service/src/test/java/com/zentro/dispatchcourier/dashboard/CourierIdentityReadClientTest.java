package com.zentro.dispatchcourier.dashboard;
import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
class CourierIdentityReadClientTest {
  @Test void forwardsCourierBearerToReadOnlySelfEndpoint() throws Exception {
    var user=UUID.randomUUID(); var profile=UUID.randomUUID();
    var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
    server.createContext("/internal/courier-dashboard/identity", exchange -> {
      assertThat(exchange.getRequestMethod()).isEqualTo("GET");
      assertThat(exchange.getRequestURI().getQuery()).isNull();
      assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-courier");
      byte[] body=("{\"userId\":\""+user+"\",\"courierProfileId\":\""+profile+"\"}").getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type","application/json");
      exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
    });server.start();
    try { var client=new CourierIdentityReadClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort());
      assertThat(client.resolve("synthetic-courier")).isEqualTo(new CourierIdentityReadClient.Identity(user,profile));
    } finally {server.stop(0);}
  }
  @Test void missingMalformedAndDeniedResponsesFailClosed() throws Exception {
    for(int status:new int[]{401,403,404,503,200}) {
      var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/internal/courier-dashboard/identity", exchange -> {
        byte[] body="{}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
        exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
      });server.start();
      try {var client=new CourierIdentityReadClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort());
        assertThatThrownBy(()->client.resolve("synthetic-courier")).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
      } finally {server.stop(0);}
    }
  }
  @Test void explicitIdentityProblemsKeepTheirStatus() throws Exception {
    for(int status:new int[]{401,403,404}) {
      String code=status==401?"COURIER_UNAUTHENTICATED":status==403?"COURIER_ACCESS_DENIED":"COURIER_PROFILE_NOT_FOUND";
      var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
      server.createContext("/internal/courier-dashboard/identity", exchange -> {
        byte[] body=("{\"code\":\""+code+"\"}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type","application/problem+json");
        exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
      });server.start();
      try {var client=new CourierIdentityReadClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort());
        assertThatThrownBy(()->client.resolve("synthetic-courier")).isInstanceOfSatisfying(ResponseStatusException.class,
            error -> assertThat(error.getStatusCode().value()).isEqualTo(status));
      } finally {server.stop(0);}
    }
  }

}
