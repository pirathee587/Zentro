package com.zentro.dispatchcourier.pod;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.zentro.dispatchcourier.courier.service.ParcelStatusClient;
import com.zentro.dispatchcourier.exception.ProofException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ProofCompletionControllerTest {
  @Test void duplicateOrConcurrentClaimIsRejectedBeforeCallingS2() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class); doReturn(0).when(jdbc).update(any(String.class), any(Object[].class));
    ParcelStatusClient parcels=mock(ParcelStatusClient.class);
    var controller=new ProofCompletionController(jdbc,parcels);
    assertThatThrownBy(()->controller.complete(java.util.UUID.randomUUID(),mock(org.springframework.security.oauth2.jwt.Jwt.class)))
        .isInstanceOf(ProofException.class).extracting(e->((ProofException)e).code()).isEqualTo("COMPLETION_IN_PROGRESS");
    org.mockito.Mockito.verifyNoInteractions(parcels);
  }
}
