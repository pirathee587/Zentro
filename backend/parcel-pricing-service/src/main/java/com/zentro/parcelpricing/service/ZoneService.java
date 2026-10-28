package com.zentro.parcelpricing.service;

import com.zentro.parcelpricing.domain.DeliveryZone;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class ZoneService {
  private static final RowMapper<DeliveryZone> ZONE_MAPPER = ZoneService::mapZone;

  private final JdbcTemplate jdbc;

  public ZoneService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<DeliveryZone> getActiveZones() {
    try {
      List<DeliveryZone> zones = jdbc.query("""
          SELECT id, code, name, description, active
            FROM pricing.delivery_zone
           WHERE active = true
           ORDER BY name ASC
          """, ZONE_MAPPER);
      if (!zones.isEmpty()) {
        return zones;
      }
    } catch (DataAccessException e) {
      // Fallback to approved zones
    }
    return defaultZones();
  }

  public boolean isValidZone(String zoneCode) {
    if (zoneCode == null || zoneCode.isBlank()) {
      return true;
    }
    return getActiveZones().stream()
        .anyMatch(z -> z.code().equalsIgnoreCase(zoneCode.trim()));
  }

  public static List<DeliveryZone> defaultZones() {
    return List.of(
        new DeliveryZone(UUID.randomUUID(), "ZONE_COLOMBO_METRO", "Colombo Metro", "Colombo District primary coverage", true),
        new DeliveryZone(UUID.randomUUID(), "ZONE_WESTERN", "Western Province (Outer)", "Gampaha and Kalutara districts", true),
        new DeliveryZone(UUID.randomUUID(), "ZONE_CENTRAL", "Central Province", "Kandy, Matale, Nuwara Eliya", true),
        new DeliveryZone(UUID.randomUUID(), "ZONE_SOUTHERN", "Southern Province", "Galle, Matara, Hambantota", true),
        new DeliveryZone(UUID.randomUUID(), "ZONE_NORTHERN", "Northern Province", "Jaffna, Kilinochchi, Mannar, Vavuniya", true)
    );
  }

  private static DeliveryZone mapZone(ResultSet rs, int rowNum) throws SQLException {
    return new DeliveryZone(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("description"),
        rs.getBoolean("active")
    );
  }
}
