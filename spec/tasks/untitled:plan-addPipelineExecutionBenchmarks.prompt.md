## Plan: Add Pipeline Execution Benchmarks

**TL;DR:** Add a JMH-based benchmark suite for the Cortex pipeline executor to establish performance baselines and enable regression detection. The benchmarks will cover single-node execution, linear pipelines, fan-out/fan-in DAGs, high concurrency scenarios, cache behavior, and dry-run vs real execution modes.

### Current State Analysis
- **No benchmarks exist** - Pipeline tests use `TestNode` with artificial delays
- **Test infrastructure is solid** - `AbstractPipelineNodeTest`, `StubLoomMedia`, `TestNode`, `CapturingNode`, AssertJ helpers
- **Executor is well-instrumented** - `ReactivePipelineExecutor` has per-node semaphores, caching, event bus, sync collector, timeout support
- **Build uses Maven** - `build.sh` runs `mvn -T 8 clean package -DskipTests`
- **CI uses GitHub Actions** - `maven-parent/.github/workflows/ci.yml` runs `mvn verify`

### Implementation Steps

#### Phase 1: JMH Infrastructure Setup
1. **Add JMH dependencies** to `cortex/pipeline-core/pom.xml`:
   - `org.openjdk.jmh:jmh-core`
   - `org.openjdk.jmh:jmh-generator-annprocess`
   - Configure `jmh-maven-plugin` for benchmark generation

2. **Create benchmark module structure**:
   ```
   cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/
   ```

3. **Add benchmark profile** to `cortex/pipeline-core/pom.xml` for running benchmarks separately from unit tests

#### Phase 2: Core Benchmark Classes
4. **Create `BenchmarkBase` abstract class** with:
   - Shared executor setup/teardown
   - Media generation utilities (various sizes: 1KB, 1MB, 10MB, 100MB)
   - Pipeline construction helpers
   - Result collection and reporting

5. **Implement benchmark scenarios**:

   **A. Single Node Execution** (`SingleNodeBenchmark`)
   - Hash nodes (SHA512, MD5, SHA256) with varying file sizes
   - CPU-bound nodes (simulated with busy wait)
   - I/O-bound nodes (simulated with Thread.sleep)
   - Metrics: throughput (ops/sec), latency (p50/p95/p99)

   **B. Linear Pipeline** (`LinearPipelineBenchmark`)
   - 5-node chain: source → hash → filter → processor → sink
   - 100 media items
   - Varying `maxConcurrentMedia` (1, 4, 8, 16, 32)
   - Metrics: end-to-end latency, throughput, per-node latency

   **C. Fan-out Pipeline** (`FanOutPipelineBenchmark`)
   - 1 source → 10 parallel processors → 1 sink
   - Tests per-node concurrency limiting
   - Metrics: parallelism efficiency, contention

   **D. High Concurrency** (`HighConcurrencyBenchmark`)
   - `maxConcurrentMedia = 100`
   - Many small media items
   - Metrics: scheduler overhead, memory usage, backpressure behavior

   **E. Cache Behavior** (`CacheBenchmark`)
   - Cache hit vs miss ratios (0%, 25%, 50%, 75%, 100%)
   - Different cache providers: `HeapNodeCache`, `NoOpNodeCache`
   - Metrics: cache overhead, hit latency vs miss latency

   **F. Dry-run vs Real** (`DryRunBenchmark`)
   - Same pipeline in dry-run vs normal mode
   - Metrics: overhead of dry-run checking

   **G. Event Bus Overhead** (`EventBusBenchmark`)
   - With/without event bus subscribers
   - Tracking events vs completion events
   - Metrics: event publishing overhead

   **H. Sync Collector** (`SyncCollectorBenchmark`)
   - `DefaultLoomBulkSyncCollector` with varying batch sizes
   - Metrics: batch flush throughput, memory pressure

#### Phase 3: Real Node Integration
6. **Create benchmarks using real Cortex nodes** via `CortexNodeAdapter`:
   - SHA512Node (hash computation)
   - FingerprintNode (video fingerprinting)
   - QualityNode (image/video quality metrics)
   - ThumbnailNode (contact sheet generation)
   - Use test media files from `cortex/nodes/*/core/src/test/resources/`

#### Phase 4: CI Integration & Reporting
7. **Add benchmark script** `run-benchmarks.sh`:
   - Builds benchmark JAR
   - Runs benchmarks with appropriate JVM flags
   - Outputs JSON/CSV results
   - Compares against baseline (stored in repo)

8. **Add GitHub Actions workflow** `.github/workflows/benchmarks.yml`:
   - Runs weekly (cron)
   - On PR to main branch
   - Fails if >10% regression detected
   - Uploads results as artifacts

9. **Create baseline results document** `BENCHMARK_BASELINES.md`:
   - Machine specs (CPU, RAM, JDK version)
   - Baseline numbers for each benchmark
   - Date of baseline capture

#### Phase 5: Documentation
10. **Update `PIPELINE.md`** with:
    - Benchmark section referencing `BENCHMARK_BASELINES.md`
    - How to run benchmarks locally
    - Interpreting results

### Relevant Files to Modify/Create

**New Files:**
- `cortex/pipeline-core/pom.xml` (add JMH deps + plugin)
- `cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/BenchmarkBase.java`
- `cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/SingleNodeBenchmark.java`
- `cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/LinearPipelineBenchmark.java`
- `cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/FanOutPipelineBenchmark.java`
- `cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/HighConcurrencyBenchmark.java`
- `cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/CacheBenchmark.java`
- `cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/DryRunBenchmark.java`
- `cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/EventBusBenchmark.java`
- `cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/SyncCollectorBenchmark.java`
- `cortex/pipeline-core/src/benchmark/java/io/metaloom/cortex/pipeline/benchmark/RealNodeBenchmark.java`
- `run-benchmarks.sh` (at repo root or cortex/)
- `.github/workflows/benchmarks.yml`
- `BENCHMARK_BASELINES.md`

**Modified Files:**
- `cortex/pipeline-core/pom.xml` (JMH configuration)
- `build.sh` (optional: add benchmark phase)
- `PIPELINE.md` (documentation)

### Verification Steps

1. **Build verification**: `mvn -T 8 clean package -DskipTests -pl cortex/pipeline-core` succeeds with JMH plugin
2. **Benchmark execution**: `java -jar cortex/pipeline-core/target/benchmarks.jar` runs all benchmarks
3. **Output format**: Results in JSON/CSV with throughput, latency percentiles
4. **CI integration**: GitHub Actions workflow runs and uploads artifacts
5. **Regression detection**: Baseline comparison script flags >10% regressions
6. **Documentation**: `PIPELINE.md` updated with benchmark section

### Decisions & Assumptions

- **JMH over custom harness**: Industry standard, integrates with Maven, supports forking, warmup, measurement iterations
- **Separate benchmark source set**: Uses `src/benchmark` (not `src/test`) to avoid running in standard test phase
- **Baseline stored in repo**: `BENCHMARK_BASELINES.md` committed for version-controlled baselines
- **Weekly CI runs**: Balances detection speed with CI resource usage
- **10% regression threshold**: Configurable, starts conservative
- **Real nodes optional initially**: Start with `TestNode` simulations, add real nodes in Phase 3

### Further Considerations

1. **Machine variability**: CI runners differ; consider normalizing by baseline run on same machine
2. **JVM warmup**: JMH handles this, but ensure sufficient warmup iterations (5-10)
3. **GC impact**: Use `-XX:+UseG1GC` or ZGC for consistent results
4. **Media file generation**: Benchmark should generate test files on-the-fly or use embedded resources
5. **Profiling integration**: Consider adding async-profiler or JFR for deeper analysis
6. **Historical tracking**: Could integrate with GitHub Pages or external dashboard for trend visualization