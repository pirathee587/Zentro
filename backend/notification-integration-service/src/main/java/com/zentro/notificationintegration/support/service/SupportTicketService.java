package com.zentro.notificationintegration.support.service;
import com.zentro.notificationintegration.support.dto.*;
import com.zentro.notificationintegration.support.repository.SupportTicketRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
@Service
public class SupportTicketService {
  private final SupportTicketRepository repository;
  public SupportTicketService(SupportTicketRepository repository) { this.repository=repository; }
  @Transactional public SupportTicketResponse create(String owner,SupportTicketRequest request) {
    return repository.insert(owner,request);
  }
  @Transactional(readOnly=true) public SupportTicketResponse find(String owner,UUID id) {
    return repository.find(id,owner).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
