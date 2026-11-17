package com.zentro.parcelpricing.assignment;
import static org.assertj.core.api.Assertions.*;
import java.util.UUID;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
class AssignmentOutcomeClientTest {
 @Test void consumesS3OutcomeIncludingExtraSettledFieldAndForwardsOnlyBearer() throws Exception {
   UUID id=UUID.randomUUID(),parcel=UUID.randomUUID();var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/internal/dispatch/assignment-operations/",exchange->{
     assertThat(exchange.getRequestMethod()).isEqualTo("GET");assertThat(exchange.getRequestURI().getPath()).endsWith(id.toString());
     assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
     byte[] body=("{\"operationId\":\""+id+"\",\"parcelId\":\""+parcel+"\",\"actorSubject\":\"dispatcher\",\"mode\":\"ASSIGN\",\"state\":\"COMMITTED\",\"settled\":false}").getBytes(StandardCharsets.UTF_8);
     exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
   });server.start();
   try{var client=new AssignmentOutcomeClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort());assertThat(client.outcome(id,"synthetic-token")).isEqualTo(new AssignmentOutcomeClient.Outcome(id,parcel,"dispatcher","ASSIGN","COMMITTED"));}finally{server.stop(0);}
 }
 @Test void invalidOrUnavailableOutcomeNeverAuthorizesClaimRelease() throws Exception {
   for(int status:new int[]{200,401,403,404,503}){
     var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
     server.createContext("/internal/dispatch/assignment-operations/",exchange->{byte[] body="{}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
     try{var client=new AssignmentOutcomeClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort());assertThatThrownBy(()->client.outcome(UUID.randomUUID(),"synthetic-token")).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode().value()).isEqualTo(503));}finally{server.stop(0);}
   }
 }
}
