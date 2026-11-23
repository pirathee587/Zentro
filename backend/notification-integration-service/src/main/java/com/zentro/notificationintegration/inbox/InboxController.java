package com.zentro.notificationintegration.inbox;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/notifications")
public class InboxController {
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(InboxController.class);
  private final InboxRepository inbox;
  private final InboxStream streams;
  public InboxController(InboxRepository inbox,InboxStream streams) { this.inbox=inbox; this.streams=streams; }
  record Owner(String subject,String portal,Instant expires) {}
  public record Page(List<InboxRepository.Item> items,long unread,long total,int page) {}
  private void requireAvailable() {
    if(!inbox.available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Notifications are not available yet.");
  }
  @ExceptionHandler(org.springframework.dao.DataAccessException.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public org.springframework.http.ProblemDetail databaseUnavailable(org.springframework.dao.DataAccessException error) {
    // Never disguise a database failure as an empty inbox, and keep the real cause visible to operators.
    LOG.error("Notification inbox database operation failed", error);
    return org.springframework.http.ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,"Notifications are temporarily unavailable.");
  }
  static Owner owner(Authentication auth,String portal) {
    if (!(auth instanceof JwtAuthenticationToken token) || !auth.isAuthenticated())
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    Jwt jwt=token.getToken();
    if (!Set.of("CUSTOMER","MERCHANT","COURIER","DISPATCHER").contains(portal)
        || auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("ROLE_"+portal)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    if(jwt.getSubject()==null || jwt.getSubject().isBlank() || jwt.getExpiresAt()==null || !jwt.getExpiresAt().isAfter(Instant.now()))
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    return new Owner(jwt.getSubject(),portal,jwt.getExpiresAt());
  }
  @GetMapping public Page list(Authentication auth,@RequestParam("portal") String portal,@RequestParam(name="page",defaultValue="0") int page) {
    Owner o=owner(auth,portal);
    if(page<0 || page>10000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    requireAvailable();
    var summary=inbox.summary(o.subject(),o.portal());
    return new Page(inbox.list(o.subject(),o.portal(),page),summary.unread(),summary.total(),page);
  }
  @PostMapping("/{id}/read") public void read(Authentication auth,@RequestParam("portal") String portal,@PathVariable("id") UUID id) {
    Owner o=owner(auth,portal);
    requireAvailable();
    if(!inbox.read(o.subject(),o.portal(),id)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
  }
  @PostMapping("/read-all") public void readAll(Authentication auth,@RequestParam("portal") String portal) {
    Owner o=owner(auth,portal); requireAvailable(); inbox.readAll(o.subject(),o.portal());
  }
  @GetMapping(value="/events",produces="text/event-stream")
  public SseEmitter events(Authentication auth,@RequestParam("portal") String portal,jakarta.servlet.http.HttpServletResponse response) {
    Owner o=owner(auth,portal); requireAvailable();
    response.setHeader("Cache-Control","no-store"); response.setHeader("X-Accel-Buffering","no");
    return streams.subscribe(o);
  }
}
