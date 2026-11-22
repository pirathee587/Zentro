package com.zentro.dispatchcourier.pod;

import com.zentro.dispatchcourier.exception.ProofException;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

/** Backend-only private bucket adapter. It never returns a service credential or public object URL. */
@Component
public class SupabaseProofStorage {
  private final RestClient http; private final String base; private final String key; private final String bucket;
  public SupabaseProofStorage(RestClient.Builder builder, @Value("${zentro.pod.storage-url:}") String base,
      @Value("${zentro.pod.storage-service-key:}") String key,
      @Value("${zentro.pod.storage-bucket:swiftdrop-delivery-proofs}") String bucket) {
    this.http=builder.build(); this.base=base.replaceAll("/+$", ""); this.key=key; this.bucket=bucket;
  }
  public String upload(UUID proofId, int revision, MultipartFile file, String type) {
    if (base.isBlank() || key.isBlank()) throw new ProofException(503,"PROOF_STORAGE_UNAVAILABLE","Proof photo storage is unavailable.");
    String path="proofs/"+proofId+"/"+revision+"-"+UUID.randomUUID()+extension(type);
    try {
      http.post().uri(base+"/storage/v1/object/"+bucket+"/"+path).header("Authorization","Bearer "+key)
          .header("apikey",key).header("x-upsert","false").contentType(MediaType.parseMediaType(type))
          .body(file.getBytes()).retrieve().toBodilessEntity();
      return path;
    } catch (IOException | RuntimeException failure) { throw new ProofException(503,"PROOF_STORAGE_UNAVAILABLE","Proof photo storage is unavailable."); }
  }
  public void uploadToPath(String path, MultipartFile file, String type) {
    if (base.isBlank() || key.isBlank()) throw new ProofException(503,"PROOF_STORAGE_UNAVAILABLE","Proof photo storage is unavailable.");
    try { http.post().uri(base+"/storage/v1/object/"+bucket+"/"+path).header("Authorization","Bearer "+key).header("apikey",key).header("x-upsert","false").contentType(MediaType.parseMediaType(type)).body(file.getBytes()).retrieve().toBodilessEntity(); }
    catch (IOException | RuntimeException failure) { throw new ProofException(503,"PROOF_STORAGE_UNAVAILABLE","Proof photo storage is unavailable."); }
  }
  /** Dispatcher-only caller supplies an object path read from the revision table. */
  public String signedUrl(String path) {
    if (base.isBlank() || key.isBlank() || path == null || !path.startsWith("proofs/"))
      throw new ProofException(503,"PROOF_STORAGE_UNAVAILABLE","Proof photo storage is unavailable.");
    try {
      @SuppressWarnings("unchecked") var result = http.post().uri(base+"/storage/v1/object/sign/"+bucket+"/"+path)
          .header("Authorization","Bearer "+key).header("apikey",key).contentType(MediaType.APPLICATION_JSON)
          .body(java.util.Map.of("expiresIn",300)).retrieve().body(java.util.Map.class);
      Object signed = result == null ? null : result.get("signedURL");
      if (!(signed instanceof String value) || value.isBlank()) throw new IllegalStateException();
      return base+"/storage/v1"+value;
    } catch (RuntimeException failure) { throw new ProofException(503,"PROOF_STORAGE_UNAVAILABLE","Proof photo storage is unavailable."); }
  }
  private static String extension(String type) { return switch(type) { case "image/jpeg" -> ".jpg"; case "image/png" -> ".png"; default -> ".webp"; }; }
}
