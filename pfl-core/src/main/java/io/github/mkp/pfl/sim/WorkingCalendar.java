package io.github.mkp.pfl.sim;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * When a team works. People only start tasks inside working hours; once started they finish them.
 *
 * @param zone the team's time zone
 * @param open start of the working day
 * @param close end of the working day
 * @param days working days
 */
public record WorkingCalendar(ZoneId zone, LocalTime open, LocalTime close, Set<DayOfWeek> days) {

  public WorkingCalendar {
    days = Set.copyOf(days);
    if (!open.isBefore(close)) {
      throw new IllegalArgumentException("a working day must open before it closes");
    }
  }

  public static final WorkingCalendar ALWAYS = new WorkingCalendar(
      ZoneId.of("UTC"), LocalTime.MIN, LocalTime.MAX, EnumSet.allOf(DayOfWeek.class));

  public static WorkingCalendar weekdays(String zone, int openHour, int closeHour) {
    return new WorkingCalendar(ZoneId.of(zone), LocalTime.of(openHour, 0), LocalTime.of(closeHour, 0),
        EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
  }

  public boolean isOpen(Instant t) {
    ZonedDateTime z = t.atZone(zone);
    LocalTime time = z.toLocalTime();
    return days.contains(z.getDayOfWeek()) && !time.isBefore(open) && time.isBefore(close);
  }

  /** The first instant at or after t when the team is working. */
  public Instant nextOpen(Instant t) {
    if (isOpen(t)) {
      return t;
    }
    ZonedDateTime z = t.atZone(zone);
    ZonedDateTime candidate = z.toLocalTime().isBefore(open) ? z.with(open) : z.plusDays(1).with(open);
    for (int i = 0; i < 14; i++) {
      if (days.contains(candidate.getDayOfWeek())) {
        return candidate.toInstant();
      }
      candidate = candidate.plusDays(1).with(open);
    }
    throw new IllegalStateException("calendar has no working day");
  }
}
