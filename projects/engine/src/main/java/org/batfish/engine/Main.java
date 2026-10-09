package org.batfish.engine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runs one tenant's engine. Configured by environment:
 *
 * <ul>
 *   <li>{@code ENGINE_PORT}: HTTP port (default 9800).
 *   <li>{@code ENGINE_STORAGE}: directory for snapshots (default a temporary directory). Contents
 *       are a cache: the engine can always be rebuilt from NOCFlow's revisions.
 *   <li>{@code ENGINE_MAX_DATAPLANES}: data planes kept in memory (default 4).
 * </ul>
 */
public final class Main {

  private Main() {}

  public static void main(String[] args) throws IOException {
    int port = Integer.parseInt(env("ENGINE_PORT", "9800"));
    int maxDataPlanes = Integer.parseInt(env("ENGINE_MAX_DATAPLANES", "4"));
    String storage = System.getenv("ENGINE_STORAGE");
    Path base =
        storage == null || storage.isEmpty()
            ? Files.createTempDirectory("engine")
            : Files.createDirectories(Paths.get(storage));
    EngineService service = new EngineService(new EngineBatfish(base, maxDataPlanes));
    EngineServer server = new EngineServer(service, new InetSocketAddress(port));
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  server.close();
                  service.close();
                }));
    server.start();
    System.out.printf("engine listening on %d, storage %s%n", server.port(), base);
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? fallback : value;
  }
}
