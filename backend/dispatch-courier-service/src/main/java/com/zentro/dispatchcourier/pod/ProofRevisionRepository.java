package com.zentro.dispatchcourier.pod;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.multipart.MultipartFile;

@Repository public class ProofRevisionRepository {
  private final JdbcTemplate jdbc; public ProofRevisionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
  public int next(UUID proofId){ return jdbc.queryForObject("SELECT coalesce(max(revision_no),0)+1 FROM dispatch.proof_revision WHERE proof_id=?",Integer.class,proofId); }
  @org.springframework.transaction.annotation.Transactional
  public void save(UUID proofId,int rev,String path,String type,MultipartFile photo,String subject){
    try { byte[] data=photo.getBytes(); jdbc.update("INSERT INTO dispatch.proof_revision(id,proof_id,revision_no,object_path,original_filename,content_type,byte_size,content_sha256,submitted_by_subject) VALUES (?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),proofId,rev,path,safe(photo.getOriginalFilename()),type,data.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)),subject);
      UUID revision=jdbc.queryForObject("SELECT id FROM dispatch.proof_revision WHERE proof_id=? AND revision_no=?",UUID.class,proofId,rev);
      jdbc.update("INSERT INTO dispatch.proof_review(id,revision_id,decision) VALUES (?,?, 'PENDING_REVIEW')",UUID.randomUUID(),revision);
    } catch(Exception e){ throw new IllegalStateException("Could not persist proof photo metadata",e); }
  }
  private static String safe(String name){return name==null?"delivery-photo":name.replaceAll("[^A-Za-z0-9._-]","_");}
}
