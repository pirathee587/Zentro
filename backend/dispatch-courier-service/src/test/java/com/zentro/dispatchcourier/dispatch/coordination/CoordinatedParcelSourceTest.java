package com.zentro.dispatchcourier.dispatch.coordination;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.UUID;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import com.zentro.dispatchcourier.dispatch.service.ParcelSource;
class CoordinatedParcelSourceTest {
 @Test void eligibleCourierSelectionNeverAcquiresAClaimOrWritesAnOperation(){
   var operations=mock(AssignmentOperationStore.class);var claims=mock(ParcelClaimClient.class);var decoder=mock(JwtDecoder.class);
   var source=new CoordinatedParcelSource(operations,claims,decoder);var id=UUID.randomUUID();
   var parcel=new ParcelSource.Parcel(id,"SYNTHETIC","Area A","Area B",Instant.now());when(claims.candidate(id,"synthetic-token")).thenReturn(parcel);
   UUID selected=source.inspectEligibleParcel(id,"synthetic-token",p->p.parcelId());assertThat(selected).isEqualTo(id);
   verify(claims).candidate(id,"synthetic-token");verifyNoMoreInteractions(claims);verifyNoInteractions(operations,decoder);
 }
}
