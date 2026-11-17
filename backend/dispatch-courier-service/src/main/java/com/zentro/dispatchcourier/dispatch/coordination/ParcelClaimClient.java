package com.zentro.dispatchcourier.dispatch.coordination;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import com.zentro.dispatchcourier.dispatch.service.ParcelSource;

@Component
public class ParcelClaimClient {
  public record Request(UUID parcelId,String mode) {
  }
  public record Claim(UUID operationId,UUID parcelId,String state,ParcelSource.Parcel parcel) {
  }
  private final RestClient http;
  public ParcelClaimClient(RestClient.Builder builder,@Value("${zentro.pod.parcel-base-url:http://localhost:8082}")String baseUrl) {
    var transport=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    var factory=new JdkClientHttpRequestFactory(transport);
    factory.setReadTimeout(Duration.ofSeconds(5));
    http=builder.baseUrl(baseUrl).requestFactory(factory).build();
  }
  public ParcelSource.Parcel candidate(UUID id,String token) {
    return call(()-> {
      var result=http.get().uri("/internal/assignment-claims/candidates/{id}",id).headers(h->h.setBearerAuth(token)).retrieve().body(ParcelSource.Parcel.class);
      if(result==null||!id.equals(result.parcelId())||result.createdAt()==null)throw unavailable();
      return result;
    });
  }
  public ParcelSource.Snapshot candidates(String token) {
    return call(()-> {
      var snapshot=http.get().uri("/internal/assignment-claims/candidates").headers(h->h.setBearerAuth(token)).retrieve().body(ParcelSource.Snapshot.class);
      if(snapshot==null||snapshot.parcels()==null||snapshot.parcels().size()>5000)throw unavailable();
      var seen=new java.util.HashSet<UUID>();
      if(snapshot.parcels().stream().anyMatch(p->p==null||p.parcelId()==null||p.createdAt()==null||!seen.add(p.parcelId())))throw unavailable();
      return snapshot;
    });
  }
  public Claim acquire(AssignmentOperationStore.Operation op,String token) {
    return operation(op,token,"acquire","HELD");
  }
  public Claim finish(AssignmentOperationStore.Operation op,String token) {
    if(!"COMMITTED".equals(op.state())&&!"ABORTED".equals(op.state()))throw unavailable();
    return operation(op,token,"COMMITTED".equals(op.state())?"confirm":"release","COMMITTED".equals(op.state())?"CONFIRMED":"RELEASED");
  }
  private Claim operation(AssignmentOperationStore.Operation op,String token,String action,String expected) {
    return call(()-> {
      var claim=http.post().uri("/internal/assignment-claims/{id}/{action}",op.operationId(),action)
      .headers(h->h.setBearerAuth(token)).body(new Request(op.parcelId(),op.mode())).retrieve().body(Claim.class);
      if(claim==null||!op.operationId().equals(claim.operationId())||!op.parcelId().equals(claim.parcelId())||!expected.equals(claim.state()))throw unavailable();
      if("HELD".equals(expected)&&(claim.parcel()==null||!op.parcelId().equals(claim.parcel().parcelId())||claim.parcel().createdAt()==null))throw unavailable();
      return claim;
    });
  }
  private <T>T call(java.util.function.Supplier<T> request) {
    try {
      return request.get();
    }
    catch(RestClientResponseException failure) {
      int status=failure.getStatusCode().value();
      if(status==401||status==403||status==404||status==409)throw new ResponseStatusException(HttpStatus.valueOf(status),"Parcel coordination request was rejected.");
      throw unavailable();
    }
    catch(RestClientException|IllegalArgumentException failure) {
      throw unavailable();
    }
  }
  private static ResponseStatusException unavailable() {
    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Parcel coordination is unavailable; its outcome may require reconciliation.");
  }
}
