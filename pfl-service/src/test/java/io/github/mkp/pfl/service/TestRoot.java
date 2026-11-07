package io.github.mkp.pfl.service;

import io.github.mkp.pfl.backtest.BacktestSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

/** A throwaway copy of the repository's engagement files, so tests can change them. */
final class TestRoot {

  static final BacktestSettings FAST = new BacktestSettings(2, 100, 3, List.of("inquiryType", "channel"), 20, 0.9, 0.25,
      ZoneId.of("Europe/Berlin"));

  private TestRoot() {}

  static Path copy() throws IOException {
    Path tmp = Files.createTempDirectory("pfl-root");
    for (String dir : List.of("config", "processes", "proposals")) {
      copyTree(Path.of("..", dir), tmp.resolve(dir));
    }
    Files.createDirectories(tmp.resolve("data"));
    Files.copy(Path.of("../data/history-sample.jsonl"), tmp.resolve("data/history-sample.jsonl"));
    return tmp;
  }

  static Engagement engagement(Path root) throws IOException {
    return new Engagement(root, ServiceConfig.load(root.resolve("config/service.json")), FAST);
  }

  private static void copyTree(Path from, Path to) throws IOException {
    try (Stream<Path> files = Files.walk(from)) {
      for (Path f : files.toList()) {
        Path target = to.resolve(from.relativize(f).toString());
        if (Files.isDirectory(f)) {
          Files.createDirectories(target);
        } else {
          Files.copy(f, target, StandardCopyOption.REPLACE_EXISTING);
        }
      }
    }
  }
}
