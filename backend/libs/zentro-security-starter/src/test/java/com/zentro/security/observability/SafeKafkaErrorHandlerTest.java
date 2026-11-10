package com.zentro.security.observability;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.FixedBackOff;

class SafeKafkaErrorHandlerTest {
  @Test void recordlessFailuresKeepExistingExceptionTypeWithoutSensitiveCause() {
    var handler = new SafeKafkaErrorHandler(new FixedBackOff(0, 0), PropagationContext.noop());
    assertThatThrownBy(() -> handler.handleOtherException(new IllegalStateException("private-password"), null, null, false))
        .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("private-password").hasNoCause();
  }
}
