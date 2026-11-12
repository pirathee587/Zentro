package com.zentro.identitypartner.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.UnknownFieldSet;
import com.zentro.contracts.identity.v1.*;
import com.zentro.contracts.parcel.v1.*;
import org.junit.jupiter.api.Test;

/** Pins published v1 wire tags using generated code, including unknown-field preservation. */
class ProtobufWireCompatibilityTest {
  @Test void identityV1GoldenWireAndUnknownFieldsRemainReadable() throws Exception {
    assertThat(GetMerchantStatusRequest.parseFrom(new byte[] {10, 1, 109}).getMerchantId()).isEqualTo("m");
    assertThat(GetCourierProfileRequest.parseFrom(new byte[] {10, 1, 99}).getCourierId()).isEqualTo("c");
    var merchant = MerchantStatusResponse.newBuilder().setMerchantId("m").setStatus("APPROVED").build();
    assertThat(merchant.toByteArray()).containsExactly(10, 1, 109, 18, 8, 65, 80, 80, 82, 79, 86, 69, 68);
    var courier = CourierProfileResponse.parseFrom(new byte[] {10, 1, 99, 16, 1, 120, 1});
    assertThat(courier.getActive()).isTrue();
    assertThat(courier.getUnknownFields().hasField(15)).isTrue();
    assertThat(CourierProfileResponse.parseFrom(courier.toByteArray())).isEqualTo(courier);
    assertThat(IdentityPartnerServiceGrpc.getServiceDescriptor().getMethods())
        .extracting(io.grpc.MethodDescriptor::getBareMethodName)
        .containsExactly("GetMerchantStatus", "GetCourierProfile");
  }

  @Test void parcelV1GoldenWireAndAdditiveFieldsRemainReadable() throws Exception {
    assertThat(GetParcelRequest.parseFrom(new byte[] {10, 1, 112}).getParcelId()).isEqualTo("p");
    var parcel = ParcelResponse.parseFrom(new byte[] {10, 1, 112, 18, 1, 116, 26, 1, 115, 120, 1});
    assertThat(parcel.getParcelId()).isEqualTo("p");
    assertThat(parcel.getTrackingNumber()).isEqualTo("t");
    assertThat(parcel.getStatus()).isEqualTo("s");
    assertThat(parcel.getUnknownFields()).isNotEqualTo(UnknownFieldSet.getDefaultInstance());
    assertThat(ParcelResponse.parseFrom(parcel.toByteArray())).isEqualTo(parcel);
    assertThat(ParcelPricingServiceGrpc.getServiceDescriptor().getMethods())
        .extracting(io.grpc.MethodDescriptor::getBareMethodName).containsExactly("GetParcel");
  }
}
