package org.batfish.engine;

import static com.google.common.base.Preconditions.checkNotNull;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.collections4.map.LRUMap;
import org.batfish.common.BatfishLogger;
import org.batfish.common.NetworkSnapshot;
import org.batfish.config.Settings;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.DataPlane;
import org.batfish.datamodel.collections.BgpAdvertisementsByVrf;
import org.batfish.identifiers.NetworkId;
import org.batfish.identifiers.SnapshotId;
import org.batfish.main.Batfish;
import org.batfish.storage.FileBasedStorage;
import org.batfish.storage.StorageProvider;
import org.batfish.vendor.VendorConfiguration;

/**
 * One tenant's embedded Batfish.
 *
 * <p>The engine owns a storage base on local disk and the caches that outlive any one request. A
 * {@link Batfish} instance is bound to one snapshot (and optionally a reference snapshot) at
 * construction, so the engine makes a cheap instance per request that shares these caches, the same
 * way Batfish's own worker does.
 *
 * <p>Snapshot ids are never reused: Batfish's topology caches are static and keyed by snapshot, so
 * a reused id in a long-lived JVM would answer from a stale topology.
 */
public final class EngineBatfish {

  /** The single network every snapshot of this tenant lives in. */
  public static final NetworkId NETWORK = new NetworkId("tenant");

  private static final AtomicLong SNAPSHOT_COUNTER = new AtomicLong();

  private final Path _storageBase;
  private final BatfishLogger _logger;
  private final FileBasedStorage _storage;
  private final Cache<NetworkSnapshot, SortedMap<String, Configuration>> _configurations;
  private final Cache<NetworkSnapshot, DataPlane> _dataPlanes;
  private final Map<NetworkSnapshot, SortedMap<String, BgpAdvertisementsByVrf>> _environmentBgp;
  private final Cache<NetworkSnapshot, Map<String, VendorConfiguration>> _vendorConfigurations;

  public EngineBatfish(@Nonnull Path storageBase, int maxDataPlanes) {
    _storageBase = checkNotNull(storageBase);
    _logger = new BatfishLogger("warn", false);
    _storage = new FileBasedStorage(storageBase, _logger);
    _configurations = Caffeine.newBuilder().softValues().maximumSize(2L * maxDataPlanes).build();
    _dataPlanes = Caffeine.newBuilder().softValues().maximumSize(maxDataPlanes).build();
    _environmentBgp = Collections.synchronizedMap(new LRUMap<>(2 * maxDataPlanes));
    _vendorConfigurations = Caffeine.newBuilder().softValues().maximumSize(maxDataPlanes).build();
  }

  /** A snapshot id that has never been used in this JVM. */
  public static @Nonnull SnapshotId freshSnapshotId(@Nonnull String prefix) {
    return new SnapshotId(prefix + "-" + SNAPSHOT_COUNTER.incrementAndGet());
  }

  public static @Nonnull NetworkSnapshot snapshot(@Nonnull SnapshotId id) {
    return new NetworkSnapshot(NETWORK, id);
  }

  public @Nonnull StorageProvider storage() {
    return _storage;
  }

  public @Nonnull Path storageBase() {
    return _storageBase;
  }

  /** A Batfish bound to {@code snapshot}, with {@code reference} for differential questions. */
  public @Nonnull Batfish batfish(@Nonnull SnapshotId snapshot, @Nullable SnapshotId reference) {
    Settings settings = new Settings(new String[] {});
    settings.setLogger(_logger);
    settings.setStorageBase(_storageBase);
    settings.setContainer(NETWORK.getId());
    settings.setTestrig(snapshot.getId());
    settings.setSnapshotName(snapshot.getId());
    settings.setDeltaTestrig(reference);
    return new Batfish(
        settings,
        _configurations,
        _dataPlanes,
        _environmentBgp,
        _vendorConfigurations,
        _storage,
        null);
  }

  /** Forgets everything cached in memory for {@code snapshot}. Disk is left to the caller. */
  public void evict(@Nonnull SnapshotId snapshot) {
    NetworkSnapshot ns = snapshot(snapshot);
    _configurations.invalidate(ns);
    _dataPlanes.invalidate(ns);
    _environmentBgp.remove(ns);
    _vendorConfigurations.invalidate(ns);
  }

  /** For tests only: seed the in-memory config cache, bypassing storage and post-processing. */
  void seedConfigurationCacheForTesting(
      @Nonnull SnapshotId snapshot, @Nonnull SortedMap<String, Configuration> configs) {
    _configurations.put(snapshot(snapshot), configs);
  }
}
