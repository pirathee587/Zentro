package com.zentro.parcelpricing.parcel.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.zentro.parcelpricing.parcel.entity.DestinationType;
import com.zentro.parcelpricing.parcel.entity.MerchantOrder;
import com.zentro.parcelpricing.parcel.entity.ParcelSize;
import com.zentro.parcelpricing.parcel.entity.ParcelStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Unit tests for G43: MerchantOrderRepository.listByMerchant deterministic sort tie-break.
 *
 * <p>The sort contract is: ORDER BY created_at DESC, id DESC. When two orders share the same
 * created_at timestamp the one with the lexicographically larger UUID (i.e. inserted later in
 * this monotonic-UUID scheme) must appear first. These tests verify:
 * <ul>
 *   <li>The repository issues a SQL query containing the full "ORDER BY created_at DESC, id DESC" clause.</li>
 *   <li>The limit is forwarded to the query without alteration.</li>
 *   <li>The merchant_id filter is scoped correctly (no cross-merchant data).</li>
 *   <li>The list of returned rows preserves the order that the database provides.</li>
 * </ul>
 */
class MerchantOrderRepositorySortTest {

  private JdbcTemplate jdbc;
  private MerchantOrderRepository repository;

  @BeforeEach
  void setUp() {
    jdbc = mock(JdbcTemplate.class);
    repository = new MerchantOrderRepository(jdbc);
  }

  @Test
  @DisplayName("G43: listByMerchant SQL contains ORDER BY created_at DESC, id DESC tie-break")
  void g43_listSqlContainsIdDescTieBreak() {
    when(jdbc.query(any(String.class), any(RowMapper.class), any(), any()))
        .thenReturn(List.of());

    repository.listByMerchant("merchant-1", 10);

    // Capture the SQL string to verify tie-break is present
    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), any(), any());

    String sql = sqlCaptor.getValue();
    assertThat(sql)
        .as("SQL must include created_at DESC, id DESC for deterministic tie-break")
        .contains("ORDER BY created_at DESC, id DESC");
  }

  @Test
  @DisplayName("G43: listByMerchant forwards limit unchanged to the query")
  void g43_limitIsForwardedToQuery() {
    when(jdbc.query(any(String.class), any(RowMapper.class), any(), any()))
        .thenReturn(List.of());

    repository.listByMerchant("merchant-2", 42);

    ArgumentCaptor<Object> argsCaptor = ArgumentCaptor.forClass(Object.class);
    verify(jdbc).query(any(String.class), any(RowMapper.class), eq("merchant-2"), eq(42));
  }

  @Test
  @DisplayName("G43: listByMerchant scopes results to merchant_id only")
  void g43_listScopedToMerchantId() {
    when(jdbc.query(any(String.class), any(RowMapper.class), any(), any()))
        .thenReturn(List.of());

    repository.listByMerchant("merchant-scoped", 50);

    ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
    verify(jdbc).query(sqlCaptor.capture(), any(RowMapper.class), eq("merchant-scoped"), eq(50));

    assertThat(sqlCaptor.getValue()).contains("merchant_id = ?");
  }

  @Test
  @DisplayName("G43: listByMerchant preserves the row order returned by the database")
  void g43_rowOrderPreservedFromDatabase() {
    UUID idA = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    UUID idB = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    OffsetDateTime sameTimestamp = OffsetDateTime.parse("2026-11-13T10:00:00+00:00");

    MerchantOrder orderB = sampleOrder(idB, "ORD-B", sameTimestamp);
    MerchantOrder orderA = sampleOrder(idA, "ORD-A", sameTimestamp);

    // DB returns B before A (because idB > idA in DESC order)
    when(jdbc.query(any(String.class), any(RowMapper.class), any(), any()))
        .thenReturn(List.of(orderB, orderA));

    List<MerchantOrder> result = repository.listByMerchant("merchant-tie", 10);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).id()).isEqualTo(idB);
    assertThat(result.get(1).id()).isEqualTo(idA);
  }

  @Test
  @DisplayName("G43: listByMerchant with limit=1 returns at most one row")
  void g43_limitOneReturnsAtMostOneRow() {
    MerchantOrder single = sampleOrder(UUID.randomUUID(), "ORD-1",
        OffsetDateTime.parse("2026-11-13T10:00:00+00:00"));
    when(jdbc.query(any(String.class), any(RowMapper.class), any(), any()))
        .thenReturn(List.of(single));

    List<MerchantOrder> result = repository.listByMerchant("merchant-3", 1);

    verify(jdbc).query(any(String.class), any(RowMapper.class), eq("merchant-3"), eq(1));
    assertThat(result).hasSize(1);
  }

  @Test
  @DisplayName("G43: listByMerchant with limit=100 (maximum clamped value) forwards 100 to query")
  void g43_limitHundredIsForwarded() {
    when(jdbc.query(any(String.class), any(RowMapper.class), any(), any()))
        .thenReturn(List.of());

    repository.listByMerchant("merchant-4", 100);

    verify(jdbc).query(any(String.class), any(RowMapper.class), eq("merchant-4"), eq(100));
  }

  // ─── Helper ─────────────────────────────────────────────────────────────────

  private static MerchantOrder sampleOrder(UUID id, String externalRef, OffsetDateTime createdAt) {
    return new MerchantOrder(
        id, "ZT-" + externalRef, "merchant-tie", externalRef,
        new MerchantOrder.Contact("Sender", "0771234567", "12 Galle Rd", "Colombo"),
        new MerchantOrder.Contact("Recipient", "0771234568", "14 Temple Rd", "Kandy"),
        new BigDecimal("1.00"), ParcelSize.SMALL, "Box", null,
        DestinationType.OTHER_DISTRICT, false, null,
        new BigDecimal("952.00"), "LKR", 3, ParcelStatus.CREATED,
        "hash-" + externalRef, createdAt, createdAt);
  }
}

