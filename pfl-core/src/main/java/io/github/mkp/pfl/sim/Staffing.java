package io.github.mkp.pfl.sim;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Who is available to do person steps.
 *
 * @param teams people per team (candidate group)
 * @param calendar when the teams work
 * @param pickupTimes optional per team: the times of day it looks at its queue. A team with pickup
 *     times only starts waiting work at those times (batching); without, it starts work as soon as
 *     someone is free.
 */
public record Staffing(Map<String, Integer> teams, WorkingCalendar calendar, Map<String, List<LocalTime>> pickupTimes) {

  public Staffing {
    teams = Map.copyOf(teams);
    pickupTimes = pickupTimes == null ? Map.of() : Map.copyOf(pickupTimes);
    teams.forEach((team, n) -> {
      if (n < 1) {
        throw new IllegalArgumentException("team " + team + " needs at least one person");
      }
    });
  }

  public Staffing(Map<String, Integer> teams, WorkingCalendar calendar) {
    this(teams, calendar, Map.of());
  }

  public Staffing withTeamSize(String team, int people) {
    Map<String, Integer> copy = new java.util.HashMap<>(teams);
    copy.put(team, people);
    return new Staffing(copy, calendar, pickupTimes);
  }
}
