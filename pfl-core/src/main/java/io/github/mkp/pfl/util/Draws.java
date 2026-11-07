package io.github.mkp.pfl.util;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

/**
 * Deterministic random draws keyed by what they are for. The same key always gives the same
 * number, so two simulations of different process variants draw the same value for the same step
 * of the same case, and a result can be reproduced from its seed alone.
 */
public final class Draws {

  private Draws() {}

  /** A random source for one purpose, e.g. {@code rng(seed, "service", caseId, nodeId, "2")}. */
  public static SplittableRandom rng(long seed, String... key) {
    long h = 0xcbf29ce484222325L ^ seed;
    for (String part : key) {
      for (byte b : part.getBytes(StandardCharsets.UTF_8)) {
        h ^= b;
        h *= 0x100000001b3L;
      }
      h ^= 0xff;
      h *= 0x100000001b3L;
    }
    return new SplittableRandom(mix(h));
  }

  /** Standard normal draw (Box–Muller). */
  public static double normal(SplittableRandom r) {
    double u1 = r.nextDouble();
    double u2 = r.nextDouble();
    return StrictMath.sqrt(-2.0 * StrictMath.log(1.0 - u1)) * StrictMath.cos(2.0 * Math.PI * u2);
  }

  /** Log-normal with the given median and 90th percentile. */
  public static double lognormal(SplittableRandom r, double median, double p90) {
    if (median <= 0) {
      return 0;
    }
    double sigma = p90 <= median ? 0 : StrictMath.log(p90 / median) / 1.2815515655446004;
    return median * StrictMath.exp(sigma * normal(r));
  }

  private static long mix(long z) {
    z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
    z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
    return z ^ (z >>> 31);
  }
}
