package com.zentro.dispatchcourier.dispatch.coordination;
import static org.assertj.core.api.Assertions.*;
import java.util.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
class ParcelClaimClientTest {
 @Test void forwardsDispatcherBearerUsesCanonicalOperationAndRejectsUntrustedBody() throws Exception {
   var op=new AssignmentOperationStore.Operation(UUID.randomUUID(),UUID.randomUUID(),"dispatcher","ASSIGN","PREPARED",false);
   var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/internal/assignment-claims/",exchange->{
     assertThat(exchange.getRequestMethod()).isEqualTo("POST");
     assertThat(exchange.getRequestURI().getPath()).endsWith(op.operationId()+"/acquire");
     assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
     assertThat(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).contains(op.parcelId().toString()).doesNotContain("dispatcher");
     byte[] body="{}".getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
   });server.start();
   try {var client=new ParcelClaimClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort());assertThatThrownBy(()->client.acquire(op,"synthetic-token")).isInstanceOf(ResponseStatusException.class);}finally{server.stop(0);}
 }
 @Test void authConflictAndUpstreamFailuresNeverBecomeSuccessfulClaims() throws Exception {
   for(int status:new int[]{401,403,404,409,500,503}){
     var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
     server.createContext("/internal/assignment-claims/",exchange->{byte[] body="PRIVATE-UPSTREAM".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
     try{var client=new ParcelClaimClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort());var op=new AssignmentOperationStore.Operation(UUID.randomUUID(),UUID.randomUUID(),"dispatcher","ASSIGN","PREPARED",false);assertThatThrownBy(()->client.acquire(op,"synthetic-token")).isInstanceOfSatisfying(ResponseStatusException.class,e->{assertThat(e.getStatusCode().value()).isEqualTo(status<500?status:503);assertThat(e.getMessage()).doesNotContain("PRIVATE-UPSTREAM");});}finally{server.stop(0);}
   }
 }
 @Test void validS2ClaimWireContractPreservesCanonicalId() throws Exception {
   UUID id=UUID.randomUUID(),parcel=UUID.randomUUID();
   var op=new AssignmentOperationStore.Operation(id,parcel,"dispatcher","ASSIGN","PREPARED",false);
   var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   server.createContext("/internal/assignment-claims/",exchange->{
     byte[] body=("{\"operationId\":\""+id+"\",\"parcelId\":\""+parcel+"\",\"state\":\"HELD\",\"parcel\":{\"parcelId\":\""+parcel+"\",\"trackingReference\":\"SYNTHETIC\",\"pickupSummary\":\"Area A\",\"dropoffSummary\":\"Area B\",\"serviceArea\":\"Area B\",\"createdAt\":\"2026-11-17T00:00:00Z\"}}").getBytes(StandardCharsets.UTF_8);
     exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
   });server.start();
   try{var client=new ParcelClaimClient(RestClient.builder(),"http://127.0.0.1:"+server.getAddress().getPort());var held=client.acquire(op,"synthetic-token");assertThat(held.parcel().parcelId()).isEqualTo(parcel);assertThat(held.parcel().effectiveServiceArea()).isEqualTo("Area B");}finally{server.stop(0);}
 }

}
