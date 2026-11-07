package io.github.mkp.pfl.sim;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Reads {@code config/staffing.json}: the teams and their working hours as the business plans them. */
public record StaffingConfig(String zone, int openHour, int closeHour, Map<String, Integer> teams) {

  public Staffing toStaffing() {
    return new Staffing(teams, WorkingCalendar.weekdays(zone, openHour, closeHour));
  }

  public static Staffing load(Path file) throws IOException {
    return new ObjectMapper().readValue(file.toFile(), StaffingConfig.class).toStaffing();
  }
}
