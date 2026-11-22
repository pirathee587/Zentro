package com.zentro.notificationintegration.inbox;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.concurrent.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Database-backed invalidation works across S5 replicas; reconnect always receives a fresh snapshot. */
@Service
public class InboxStream {
  private record Connection(InboxController.Owner owner,SseEmitter emitter) {}
  private final ConcurrentMap<Connection,String> clients=new ConcurrentHashMap<>();
  private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{
    Thread t=new Thread(r,"inbox-stream"); t.setDaemon(true); return t;
  });
  private final InboxRepository inbox;
  public InboxStream(InboxRepository inbox) {
    this.inbox=inbox; timer.scheduleWithFixedDelay(this::poll,2,2,TimeUnit.SECONDS);
  }
  public synchronized SseEmitter subscribe(InboxController.Owner owner) {
    if(clients.size()>=1000 || clients.keySet().stream().filter(c->c.owner().subject().equals(owner.subject())
        && c.owner().portal().equals(owner.portal())).count()>=5)
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS);
    SseEmitter emitter=new SseEmitter(90000L);
    Connection c=new Connection(owner,emitter);
    clients.put(c,"");
    emitter.onCompletion(()->clients.remove(c)); emitter.onTimeout(()->{clients.remove(c);emitter.complete();});
    emitter.onError(e->clients.remove(c));
    return emitter;
  }
  void poll() {
    clients.forEach((c,version)->{
      try {
        if(!c.owner().expires().isAfter(Instant.now())) { clients.remove(c); c.emitter().complete(); return; }
        var snapshot=inbox.summary(c.owner().subject(),c.owner().portal());
        String next=snapshot.version()+":"+snapshot.unread()+":"+snapshot.total();
        if(!next.equals(version)) {
          c.emitter().send(SseEmitter.event().name("inbox").data(snapshot)); clients.replace(c,next);
        } else c.emitter().send(SseEmitter.event().comment("keepalive"));
      } catch(Exception error) { clients.remove(c); c.emitter().complete(); }
    });
  }
  @PreDestroy public void stop() { timer.shutdownNow(); clients.keySet().forEach(c->c.emitter().complete()); clients.clear(); }
}
