package com.zentro.dispatchcourier.pod;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import com.zentro.dispatchcourier.exception.ProofException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DispatcherProofReviewControllerTest {
  @Test void rejectRequiresNonBlankReasonBeforeChangingStorage() {
    var controller=new DispatcherProofReviewController(mock(JdbcTemplate.class),mock(SupabaseProofStorage.class));
    assertThatThrownBy(()->controller.reject(java.util.UUID.randomUUID(),new DispatcherProofReviewController.Decision(" ",0),mock(org.springframework.security.oauth2.jwt.Jwt.class)))
        .isInstanceOf(ProofException.class).extracting(e->((ProofException)e).code()).isEqualTo("REJECTION_REASON_REQUIRED");
  }
  @Test void staleApproveVersionIsRejectedBeforeReadingUpdatedReview() {
    JdbcTemplate jdbc=mock(JdbcTemplate.class); doReturn(0).when(jdbc).update(any(String.class), any(Object[].class));
    var controller=new DispatcherProofReviewController(jdbc,mock(SupabaseProofStorage.class));
    assertThatThrownBy(()->controller.approve(java.util.UUID.randomUUID(),new DispatcherProofReviewController.Decision(null,7),mock(org.springframework.security.oauth2.jwt.Jwt.class)))
        .isInstanceOf(ProofException.class).extracting(e->((ProofException)e).code()).isEqualTo("REVIEW_CONFLICT");
  }
}
