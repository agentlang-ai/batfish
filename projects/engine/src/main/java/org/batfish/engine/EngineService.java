package org.batfish.engine;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.Hashing;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.batfish.common.Answerer;
import org.batfish.common.util.BatfishObjectMapper;
import org.batfish.datamodel.answers.AnswerElement;
import org.batfish.datamodel.questions.Question;
import org.batfish.engine.Deriver.Change;
import org.batfish.identifiers.SnapshotId;
import org.batfish.main.Batfish;

/**
 * The engine's operations, independent of HTTP: put and derive snapshots as jobs, report their
 * facts and coverage, and answer questions.
 *
 * <p>Snapshots are immutable and named by the caller. Building one runs as a job on a single
 * worker, so one tenant's engine computes one data plane at a time and memory stays bounded.
 */
public final class EngineService implements AutoCloseable {

  /** A request the engine refuses, with the HTTP status it maps to. */
  public static final class EngineException extends RuntimeException {
    private final int _status;

    EngineException(int status, String message) {
      super(message);
      _status = status;
    }

    public int status() {
      return _status;
    }
  }

  public enum Status {
    pending,
    running,
    done,
    failed
  }

  /** A job's public view. */
  public record Job(
      @Nonnull String id,
      @Nonnull String kind,
      @Nonnull String snapshot,
      @Nonnull Status status,
      @Nullable String error) {}

  /** A snapshot's public view. */
  public record SnapshotView(
      @Nonnull String id,
      @Nonnull Status status,
      @Nullable String base,
      @Nonnull List<CoverageHole> holes,
      @Nonnull List<CoverageHole> introduced,
      @Nullable String error) {}

  /** The facts endpoint's body. */
  public record Facts(
      @Nonnull Map<String, DeviceFacts> devices, @Nonnull List<CoverageHole> holes) {}

  /** A question's answer. */
  public record Answer(@Nonnull String question, @Nonnull AnswerElement answer) {}

  public record JobRef(@Nonnull String jobId, @Nonnull String snapshot) {}

  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

  private static final class Entry {
    final String id;
    final String contentHash;
    final @Nullable String base;
    volatile String jobId;
    volatile Status status = Status.pending;
    volatile @Nullable SnapshotBuilder.Built built;
    volatile List<CoverageHole> introduced = ImmutableList.of();
    volatile @Nullable String error;

    Entry(String id, String contentHash, @Nullable String base) {
      this.id = id;
      this.contentHash = contentHash;
      this.base = base;
    }
  }

  private final EngineBatfish _engine;
  private final SnapshotBuilder _builder;
  private final Deriver _deriver;
  private final ExecutorService _worker = Executors.newSingleThreadExecutor();
  private final Map<String, Entry> _snapshots = new ConcurrentHashMap<>();
  private final Map<String, Job> _jobs = new ConcurrentHashMap<>();

  public EngineService(@Nonnull EngineBatfish engine) {
    _engine = engine;
    _builder = new SnapshotBuilder(engine);
    _deriver = new Deriver(_builder);
  }

  public synchronized @Nonnull JobRef put(@Nonnull String id, @Nonnull SnapshotRequest request) {
    checkId(id);
    if (request.devices().isEmpty()) {
      throw new EngineException(400, "a snapshot needs at least one device");
    }
    long distinct =
        request.devices().stream().map(d -> d.name().toLowerCase(Locale.ROOT)).distinct().count();
    if (distinct != request.devices().size()) {
      throw new EngineException(400, "device names must be unique");
    }
    String hash = hash(request);
    Entry existing = _snapshots.get(id);
    if (existing != null) {
      if (!existing.contentHash.equals(hash)) {
        throw new EngineException(409, "snapshot " + id + " exists with different content");
      }
      return new JobRef(existing.jobId, id);
    }
    Entry entry = new Entry(id, hash, null);
    _snapshots.put(id, entry);
    return submit("build", entry, () -> _builder.build(request));
  }

  public synchronized @Nonnull JobRef derive(
      @Nonnull String baseId, @Nonnull String id, @Nonnull List<Change> changes) {
    checkId(id);
    if (changes.isEmpty()) {
      throw new EngineException(400, "a derivation needs at least one change");
    }
    Entry base = done(baseId);
    String hash = hash(Map.of("base", base.contentHash, "changes", changes));
    Entry existing = _snapshots.get(id);
    if (existing != null) {
      if (!existing.contentHash.equals(hash)) {
        throw new EngineException(409, "snapshot " + id + " exists with different content");
      }
      return new JobRef(existing.jobId, id);
    }
    SnapshotBuilder.Built built = base.built;
    var known =
        built.request().devices().stream().map(d -> d.name().toLowerCase(Locale.ROOT)).toList();
    for (Change c : changes) {
      if (!known.contains(c.device().toLowerCase(Locale.ROOT))) {
        throw new EngineException(400, "change for unknown device " + c.device());
      }
    }
    Entry entry = new Entry(id, hash, baseId);
    _snapshots.put(id, entry);
    return submit(
        "derive",
        entry,
        () -> {
          Deriver.Derived derived = _deriver.derive(built, changes);
          entry.introduced = derived.introduced();
          return derived.snapshot();
        });
  }

  public @Nonnull Job job(@Nonnull String jobId) {
    Job job = _jobs.get(jobId);
    if (job == null) {
      throw new EngineException(404, "no job " + jobId);
    }
    return job;
  }

  public @Nonnull SnapshotView snapshot(@Nonnull String id) {
    Entry e = entry(id);
    SnapshotBuilder.Built built = e.built;
    return new SnapshotView(
        e.id,
        e.status,
        e.base,
        built == null ? ImmutableList.of() : built.holes(),
        e.introduced,
        e.error);
  }

  public @Nonnull Facts facts(@Nonnull String id) {
    SnapshotBuilder.Built built = done(id).built;
    return new Facts(built.facts(), built.holes());
  }

  public @Nonnull Answer ask(
      @Nonnull String id, @Nonnull String question, @Nullable JsonNode parameters) {
    if (Questions.differentialOnly(question)) {
      throw new EngineException(400, question + " is differential; use /diff");
    }
    Question q = question(question, parameters);
    SnapshotId snap = done(id).built.id();
    Batfish bf = _engine.batfish(snap, null);
    Answerer answerer = answerer(q, bf);
    return new Answer(question, answerer.answer(EngineBatfish.snapshot(snap)));
  }

  public @Nonnull Answer diff(
      @Nonnull String baseId,
      @Nonnull String proposedId,
      @Nonnull String question,
      @Nullable JsonNode parameters) {
    Question q = question(question, parameters);
    q.setDifferential(true);
    SnapshotId base = done(baseId).built.id();
    SnapshotId proposed = done(proposedId).built.id();
    Batfish bf = _engine.batfish(proposed, base);
    Answerer answerer = answerer(q, bf);
    return new Answer(
        question,
        answerer.answerDiff(EngineBatfish.snapshot(proposed), EngineBatfish.snapshot(base)));
  }

  public synchronized void delete(@Nonnull String id) {
    Entry e = entry(id);
    if (e.status == Status.pending || e.status == Status.running) {
      throw new EngineException(409, "snapshot " + id + " is still being built");
    }
    _snapshots.remove(id);
    SnapshotBuilder.Built built = e.built;
    if (built != null) {
      _engine.evict(built.id());
      deleteRecursively(_builder.snapshotDir(built.id()));
    }
  }

  @Override
  public void close() {
    _worker.shutdownNow();
  }

  private JobRef submit(String kind, Entry entry, Supplier<SnapshotBuilder.Built> work) {
    String jobId = UUID.randomUUID().toString();
    entry.jobId = jobId;
    _jobs.put(jobId, new Job(jobId, kind, entry.id, Status.pending, null));
    _worker.submit(
        () -> {
          _jobs.put(jobId, new Job(jobId, kind, entry.id, Status.running, null));
          entry.status = Status.running;
          try {
            SnapshotBuilder.Built built = work.get();
            Batfish bf = _engine.batfish(built.id(), null);
            bf.computeDataPlane(EngineBatfish.snapshot(built.id()));
            entry.built = built;
            entry.status = Status.done;
            _jobs.put(jobId, new Job(jobId, kind, entry.id, Status.done, null));
          } catch (RuntimeException | Error ex) {
            entry.error = String.valueOf(ex.getMessage());
            entry.status = Status.failed;
            _jobs.put(jobId, new Job(jobId, kind, entry.id, Status.failed, entry.error));
          }
        });
    return new JobRef(jobId, entry.id);
  }

  private Entry entry(String id) {
    Entry e = _snapshots.get(id);
    if (e == null) {
      throw new EngineException(404, "no snapshot " + id);
    }
    return e;
  }

  private Entry done(String id) {
    Entry e = entry(id);
    if (e.status != Status.done) {
      throw new EngineException(409, "snapshot " + id + " is " + e.status);
    }
    return e;
  }

  private static Question question(String name, @Nullable JsonNode parameters) {
    if (!Questions.known(name)) {
      throw new EngineException(404, "unknown question " + name);
    }
    try {
      return Questions.create(name, parameters);
    } catch (IllegalArgumentException e) {
      throw new EngineException(400, e.getMessage());
    }
  }

  private static Answerer answerer(Question q, Batfish bf) {
    Answerer answerer = bf.createAnswerer(q);
    if (answerer == null) {
      throw new EngineException(500, "no answerer for " + q.getName());
    }
    return answerer;
  }

  private static void checkId(String id) {
    if (!ID.matcher(id).matches()) {
      throw new EngineException(400, "bad snapshot id " + id);
    }
  }

  private static String hash(Object o) {
    try {
      byte[] json = BatfishObjectMapper.writer().writeValueAsString(o).getBytes(UTF_8);
      return Hashing.sha256().hashBytes(json).toString();
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void deleteRecursively(Path dir) {
    if (!Files.exists(dir)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(dir)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
