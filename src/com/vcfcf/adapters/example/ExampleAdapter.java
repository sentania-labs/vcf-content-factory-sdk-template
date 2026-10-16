package com.vcfcf.adapters.example;

import com.vcfcf.adapter.VcfCfAdapter;
import com.vcfcf.adapter.auth.BasicAuth;
import com.vcfcf.adapter.http.HttpClientBuilder;
import com.vcfcf.adapter.http.ManagedHttpClient;
import com.vcfcf.adapter.json.SimpleJson;
import com.vcfcf.adapter.retry.RetryPolicy;
import com.vcfcf.adapter.spi.VcfCfCollector;
import com.vcfcf.adapter.spi.VcfCfDiscoverer;
import com.vcfcf.adapter.spi.VcfCfTester;

import com.integrien.alive.common.adapter3.AdapterBase;
import com.integrien.alive.common.adapter3.MetricData;
import com.integrien.alive.common.adapter3.MetricKey;
import com.integrien.alive.common.adapter3.ResourceKey;
import com.integrien.alive.common.adapter3.ResourceStatus;
import com.integrien.alive.common.adapter3.TestParam;
import com.integrien.alive.common.adapter3.config.AdapterConfig;
import com.integrien.alive.common.adapter3.config.ResourceConfig;
import com.integrien.alive.common.adapter3.config.ResourceIdentifierConfig;
import com.integrien.alive.common.util.CommonConstants.ResourceStatusEnum;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Example Tier 2 SDK adapter — the VCF Content Factory adapter <b>template</b>.
 *
 * <p>This is a deliberately minimal, fully-wired skeleton you copy when starting
 * a new pak. It demonstrates the current framework v2 idioms end-to-end with one
 * HTTP-backed resource kind ({@code ExampleResource}) hung under a {@code World}
 * traversal anchor. Replace the example HTTP calls, resource kinds, and metric
 * keys with your target system; keep the SHAPE.
 *
 * <h2>What to keep when you adapt this (the load-bearing idioms)</h2>
 * <ol>
 *   <li><b>Keyed constructors.</b> {@code super(ADAPTER_KIND[, dir, id])} so the
 *       framework default {@code onDescribe()} can resolve {@code describe.xml}
 *       during controller-side bare instantiation. Do NOT override
 *       {@code onDescribe()} and do NOT hand-roll the describe load.</li>
 *   <li><b>Collect-path discovery.</b> {@code getCollector().needsRediscovery()}
 *       returns {@code true} and {@code rediscover()} runs the SAME shared
 *       {@link #enumerateResources} body that {@code getDiscoverer()} uses.
 *       <b>This is mandatory, not optional:</b> VCF Ops 9.0.2 NEVER calls
 *       {@code onDiscover()} for these adapter3-type collectors, so an
 *       onDiscover-only adapter heartbeats GREEN but discovers zero resources
 *       forever. Driving discovery from the collect path is the only thing that
 *       populates a fresh instance.</li>
 *   <li><b>One enumeration, two callers.</b> {@link #enumerateResources} feeds a
 *       {@link ResourceSink}; the discoverer feeds {@code dr::addResource} and the
 *       collector feeds {@link #registerNewResource(ResourceConfig)}. Holding the
 *       enumeration in one method guarantees the two paths can never drift.</li>
 *   <li><b>componentLogger(Class).</b> Every helper class gets its logger from
 *       {@code componentLogger(Helper.class)} — never hand-roll a logger handle
 *       (it silently drops INFO).</li>
 *   <li><b>Cooperative cancellation.</b> The framework base sets a volatile abort
 *       flag on stop/discard; honor it in any long loop via
 *       {@code isAbortRequested()} and re-throw {@link InterruptedException}.</li>
 *   <li><b>Loud failure (no silent catch-and-continue).</b> An unreadable remote
 *       payload THROWS out of {@code collect()} so the framework marks the
 *       resource ERROR/DOWN. Never publish a 0.0 / "" sentinel for a value you
 *       failed to read — that is the exact "garbage in, looks healthy" bug the
 *       framework exists to prevent.</li>
 *   <li><b>Credential redaction.</b> The password is read once and handed only to
 *       the auth strategy. Search {@code // REDACT-SECRET} to audit. Never put a
 *       secret in a log line, exception message, or URL.</li>
 * </ol>
 *
 * <p><b>Optional Suite API stitching</b> (push relationships onto existing VCF Ops
 * resources, e.g. a VMWARE HostSystem) is intentionally omitted here to keep the
 * skeleton minimal. See {@code content/sdk-adapters/unifi} (LLDP→HostSystem) and
 * {@code content/sdk-adapters/compliance} for the {@code SuiteApiStitcher} +
 * {@code ForeignResourceResolver} pattern when your adapter needs it.
 */
public final class ExampleAdapter extends VcfCfAdapter<ExampleConfig> {

    /** Adapter kind key — MUST match {@code key} in describe.xml and adapter.yaml. */
    private static final String ADAPTER_KIND = "example_adapter";

    /** REST client for the remote system. Built in {@link #configureAdapter}. */
    private volatile ExampleApiClient api;

    /**
     * Collection interval in milliseconds; MUST match
     * {@code monitoringInterval} (minutes) of the adapter instance kind in
     * describe.xml. Used as the snapshot's maximum age.
     */
    private static final long COLLECTION_INTERVAL_MS = 5L * 60 * 1000;

    /**
     * This cycle's snapshot of the remote API: one read per cycle, shared by
     * {@code rediscover} and every per-resource {@code collect}. Never served
     * once it is one collection interval old, even if no new cycle began.
     * See {@link CycleCache}.
     */
    private final CycleCache<Snapshot> snapshots =
            new CycleCache<>(COLLECTION_INTERVAL_MS, System::currentTimeMillis);

    /**
     * Why the last configure built no client (an invalid host or port), or
     * {@code null}. Every read this cycle then fails with it (ERROR) until
     * the instance is edited.
     */
    private volatile String configError;

    // -----------------------------------------------------------------------
    // Constructors — keyed, per the framework contract. The no-arg form is hit
    // during controller-side describe (no platform injection yet); the two-arg
    // form during live collection. Both MUST pass ADAPTER_KIND to super so
    // onDescribe() can find describe.xml.
    // -----------------------------------------------------------------------

    public ExampleAdapter() {
        super(ADAPTER_KIND);
    }

    public ExampleAdapter(String adapterDir, Integer adapterInstanceId) {
        super(ADAPTER_KIND, adapterDir, adapterInstanceId);
    }

    // -----------------------------------------------------------------------
    // onDescribe — framework default. DO NOT implement. It resolves
    // describe.xml from the constructor-stored ADAPTER_KIND, which is the only
    // value available during controller-side bare instantiation.
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // configureAdapter — read config + credentials, build the HTTP client.
    // -----------------------------------------------------------------------

    @Override
    protected void configureAdapter(ResourceStatus status, ResourceConfig rc) {
        ExampleConfig cfg;
        try {
            cfg = buildConfig(rc);
        } catch (IllegalArgumentException e) {
            // Invalid host or port (ExampleConfig validates them). Build no
            // client; every read fails with this message until the instance
            // is edited, and Test Connection shows the same message.
            this.api = null;
            this.configError = e.getMessage();
            snapshots.beginCycle();
            logError("ExampleAdapter configuration is invalid: " + e.getMessage(), e);
            return;
        }
        this.configError = null;
        this.config = cfg;
        this.httpClient = buildHttpClient(cfg);
        this.api = new ExampleApiClient(this.httpClient,
                componentLogger(ExampleApiClient.class));
        // A new configuration must not be served the old one's snapshot.
        snapshots.beginCycle();

        // host/port are safe to log; allowInsecure is a lab flag. The password is
        // never referenced here. // REDACT-SECRET
        logInfo("ExampleAdapter configured: host=" + cfg.host + " port=" + cfg.port
                + " allowInsecure=" + cfg.allowInsecure);
        // One WARN per configure while validation is off, so an insecure
        // instance stays visible in the log after every edit or restart.
        String insecure = cfg.insecureWarning();
        if (insecure != null) {
            logWarn(insecure);
        }
    }

    private ExampleConfig buildConfig(ResourceConfig rc) {
        String host = getIdentifier(rc, "host");
        String port = getIdentifier(rc, "port");
        String allowInsecure = getIdentifier(rc, "allowInsecure");
        String username = getCredentialField(rc, "username");
        String password = getCredentialField(rc, "password"); // REDACT-SECRET
        return new ExampleConfig(host, port, username, password, allowInsecure);
    }

    private ManagedHttpClient buildHttpClient(ExampleConfig cfg) {
        // BasicAuth captures the credentials inside the auth strategy; the
        // password never leaves the client and is never logged. // REDACT-SECRET
        HttpClientBuilder b = HttpClientBuilder.builder()
                .baseUrl(cfg.baseUrl())
                .auth(new BasicAuth(cfg.username, cfg.password())) // REDACT-SECRET
                .retryPolicy(RetryPolicy.builder()
                        .maxAttempts(3)
                        .baseDelayMs(1000)
                        .build())
                .timeout(Duration.ofSeconds(30));
        if (cfg.allowInsecure) {
            // Lab opt-out only. Production should trust the platform store.
            b.allowInsecure(true);
        } else {
            // Honor the platform trust store (user-trusted certs, renewal).
            b.platformSsl(this);
        }
        return b.build();
    }

    // -----------------------------------------------------------------------
    // getTester — self-contained. The controller calls onTest() on a BARE
    // instance (configureAdapter has NOT run, so this.api/this.config are null).
    // Derive everything from the ResourceConfig on the TestParam, and THROW on
    // failure so the UI shows a real message (never blank-fail).
    // -----------------------------------------------------------------------

    @Override
    protected VcfCfTester<ExampleConfig> getTester() {
        return (cfg, http, param) -> {
            ResourceConfig rc = testResourceConfig(param);
            if (rc == null) {
                throw new Exception("Test-connection: no adapter-instance "
                        + "ResourceConfig on TestParam — cannot read host/credentials");
            }
            ExampleConfig testCfg = buildConfig(rc);
            ManagedHttpClient testHttp = buildHttpClient(testCfg);
            try {
                ExampleApiClient testApi = new ExampleApiClient(testHttp,
                        componentLogger(ExampleApiClient.class));
                SimpleJson items = testApi.listItems();
                SimpleJson data = items.get("data");
                if (data.isNull() || !data.isList()) {
                    // Unreadable, not "empty" — fail loud. A success-shaped but
                    // data-free payload must NOT pass the test.
                    throw new IOException("Remote /api/items returned a 200 with no "
                            + "readable 'data' list");
                }
                logInfo("Test OK: connected to " + testCfg.host + ", "
                        + data.size() + " item(s)");
            } finally {
                testHttp.discard();
            }
        };
    }

    private static ResourceConfig testResourceConfig(TestParam param) {
        if (param == null) return null;
        AdapterConfig adConf = param.getAdapterConfig();
        if (adConf == null) return null;
        return adConf.getAdapterInstResource();
    }

    // -----------------------------------------------------------------------
    // getDiscoverer — wired to the SAME shared enumeration as the collector's
    // rediscover() path. Kept for forward compatibility and any platform that
    // DOES call onDiscover(); on VCF Ops 9.0.2 discovery is driven from the
    // collect path (see getCollector below).
    // -----------------------------------------------------------------------

    @Override
    protected VcfCfDiscoverer<ExampleConfig> getDiscoverer() {
        return (cfg, http, param, dr) -> {
            logInfo("ExampleAdapter discover (onDiscover path): enumerating");
            snapshots.beginCycle(); // a discovery pass is its own cycle
            enumerateResources(currentSnapshot(), dr::addResource);
        };
    }

    /**
     * The single, shared resource-tree enumeration. Walks one snapshot of the
     * remote API and emits one {@link ResourceConfig} per resource to
     * {@code sink}. Two callers, one body: the discoverer (onDiscover path) and
     * the collector's {@code rediscover} (collect-path discovery). Holding it in
     * one method is the cardinal requirement of dual-path discovery — the two
     * paths can never drift.
     */
    private void enumerateResources(Snapshot snapshot, ResourceSink sink) {
        // World anchor: the traversal entry point, always present.
        sink.accept(rcOf("World", "Example World", "world_id", "example_world"));

        for (Map.Entry<String, SimpleJson> e : snapshot.byId.entrySet()) {
            String name = e.getValue().get("name").asString(e.getKey());
            sink.accept(rcOf("ExampleResource", name, "id", e.getKey()));
        }
        logInfo("Example enumerate: " + snapshot.byId.size() + " ExampleResource(s)");
    }

    /** The seam that lets one enumeration body feed both discovery paths. */
    @FunctionalInterface
    private interface ResourceSink {
        void accept(ResourceConfig rc);
    }

    private ResourceConfig rcOf(String kind, String name, String idKey, String idValue) {
        ResourceKey key = new ResourceKey(name, kind, ADAPTER_KIND);
        key.addIdentifier(new ResourceIdentifierConfig(idKey, idValue, true));
        return new ResourceConfig(key);
    }

    // -----------------------------------------------------------------------
    // getCollector: per-resource collect over one snapshot per cycle, with
    // collect-path discovery wired in via needsRediscovery/rediscover.
    // -----------------------------------------------------------------------

    @Override
    protected VcfCfCollector<ExampleConfig> getCollector() {
        return new VcfCfCollector<ExampleConfig>() {

            /**
             * Drive discovery from the collect path every cycle.
             *
             * <p><b>Why this MUST be true.</b> On VCF Ops 9.0.2 the server's
             * "Auto Discover" task never reaches {@code onDiscover()} for this
             * adapter3-type collector, so {@code getDiscoverer()} never runs and
             * a fresh instance heartbeats GREEN with zero resources forever. The
             * platform DOES call {@code onCollect()} on cadence; the framework
             * runs {@code rediscover()} at the top of every cycle when this
             * returns true. Returning true makes discovery self-sufficient.
             *
             * <p><b>The snapshot depends on this too.</b> {@code rediscover} is
             * also the top of the snapshot cycle ({@code snapshots.beginCycle()}).
             * If you ever make this conditional, move {@code beginCycle()} to a
             * place that runs every cycle, or every cycle that skips rediscover
             * serves the previous snapshot (until the maximum age drops it) and
             * stamps its values with the current time.
             */
            @Override
            public boolean needsRediscovery(ExampleConfig cfg) {
                return true;
            }

            /**
             * Collect-path discovery: runs the SAME shared
             * {@link #enumerateResources} body, registering each resource via
             * {@link #registerNewResource(ResourceConfig)} so new resources ride
             * this cycle's embedded DiscoveryResult and appear in VCF Ops from
             * the cycle they are first seen. Because the framework runs this
             * before the per-resource collect loop on the first cycle after
             * configure, a fresh (0-resource) instance populates on its first
             * collect.
             *
             * <p>It is also the top of the cycle for the snapshot: it forgets
             * the last cycle's snapshot and reads a fresh one, which every
             * {@code collect} below then shares.
             */
            @Override
            public void rediscover(ExampleConfig cfg, ManagedHttpClient http,
                    ResourceConfig adapterInst, AdapterBase adapter)
                    throws InterruptedException, Exception {
                // Stop/discard may land between cycles; honor it before any read.
                if (isAbortRequested()) return;
                // Top of the snapshot cycle. Runs every cycle only because
                // needsRediscovery() always returns true; see its Javadoc
                // before making that conditional.
                snapshots.beginCycle();
                logInfo("ExampleAdapter rediscover (collect-path discovery): enumerating");
                enumerateResources(currentSnapshot(),
                        ExampleAdapter.this::registerNewResource);
            }

            @Override
            public void collect(ExampleConfig cfg, ManagedHttpClient http,
                    ResourceConfig rc, List<MetricData> out, AdapterBase adapter)
                    throws InterruptedException, Exception {
                if (isAbortRequested()) return;
                String kind = rc.getResourceKind();
                if ("World".equals(kind)) {
                    return; // traversal anchor — keepalive only, no metrics
                }
                if ("ExampleResource".equals(kind)) {
                    collectExampleResource(rc, currentSnapshot(), out);
                    return;
                }
                logWarn("collect: unknown resource kind " + kind);
            }

            @Override
            public ResourceStatusEnum mapCollectException(Exception e) {
                // Distinguish unreachable (DOWN) from reachable-but-failed (ERROR)
                // so operators see the right state. Walks the cause chain, so a
                // failure wrapped by the HTTP or retry layer still maps to DOWN.
                return ExampleFailures.isUnreachable(e)
                        ? ResourceStatusEnum.RESOURCE_STATUS_DOWN
                        : ResourceStatusEnum.RESOURCE_STATUS_ERROR;
            }
        };
    }

    /**
     * One immutable read of the remote API, indexed by item id so each
     * per-resource {@code collect} is a map lookup, not a scan of the list.
     * Add a field per endpoint when your adapter reads more than one.
     */
    private static final class Snapshot {
        /** Items by id, in payload order. Unmodifiable. */
        final Map<String, SimpleJson> byId;

        Snapshot(Map<String, SimpleJson> byId) {
            this.byId = Collections.unmodifiableMap(byId);
        }
    }

    /**
     * This cycle's snapshot: read on the first call of the cycle (normally
     * {@code rediscover}), then shared by every {@code collect} in the cycle.
     */
    private Snapshot currentSnapshot() throws Exception {
        return snapshots.get(this::fetchSnapshot);
    }

    /**
     * Read the remote API once and index it.
     *
     * <p><b>Loud failure.</b> A REST error, or a success-shaped payload with no
     * readable {@code data} list, THROWS, and the framework then marks the
     * resource ERROR/DOWN. {@code SimpleJson} is null-tolerant
     * ({@code asDouble()->0.0}), so without this contract-assert an unreadable
     * response would publish a healthy-looking but data-free instance.
     */
    private Snapshot fetchSnapshot() throws Exception {
        // Read the volatile field once: onDiscard() nulls it, possibly while
        // this cycle is still running. A discarded instance stops the cycle
        // (InterruptedException) instead of failing with an NPE.
        ExampleApiClient client = this.api;
        if (client == null) {
            String invalid = this.configError;
            if (invalid != null) {
                throw new IllegalStateException("Adapter instance configuration "
                        + "is invalid: " + invalid);
            }
            throw new InterruptedException("discarded");
        }
        SimpleJson items = client.listItems();
        SimpleJson data = items.get("data");
        if (data.isNull() || !data.isList()) {
            throw new IOException("Remote /api/items returned a 200 payload with no "
                    + "readable 'data' list, treating as unreadable (no sentinel "
                    + "metrics published)");
        }
        Map<String, SimpleJson> byId = new LinkedHashMap<>();
        for (SimpleJson item : data.asList()) {
            String id = item.get("id").asString();
            if (id == null || id.isEmpty()) continue;
            byId.putIfAbsent(id, item); // first wins: one resource per id
        }
        return new Snapshot(byId);
    }

    private void collectExampleResource(ResourceConfig rc, Snapshot snapshot,
            List<MetricData> out) {
        String id = getIdentifier(rc, "id");
        SimpleJson item = id == null ? null : snapshot.byId.get(id);
        if (item == null) {
            // The resource was discovered but is absent this cycle. Skip it (the
            // framework will leave it without fresh data) — do NOT publish a
            // zero. Absence is not a measured value.
            logWarn("collect: item " + id + " absent from snapshot");
            return;
        }
        // Example numeric metric + string property. Replace with your fields.
        // metricIfPresent skips a missing or non-numeric field: a gap in the
        // chart, never a published 0.0 that looks like a real reading.
        metricIfPresent(out, "Status|value", item.get("value"));
        prop(out, "Configuration|name", item.get("name").asString(""));
    }

    // -----------------------------------------------------------------------
    // MetricData append helpers.
    //   metricIfPresent() -> numeric, only when the JSON field holds a number.
    //               Use this for every metric read from a payload.
    //   metric() -> numeric  (new MetricKey(key), isProperty=false)
    //   prop()   -> string property (new MetricKey(true, key)) — REQUIRED for
    //               strings; the platform silently discards string values on a
    //               non-property MetricKey.
    // -----------------------------------------------------------------------

    /**
     * Publish {@code field} as a numeric metric only when it is present and
     * numeric. {@code SimpleJson.asDouble()} returns {@code 0.0} for a
     * missing, null or non-numeric field, so calling {@code metric(out, key,
     * field.asDouble())} directly publishes absence as a zero. Absence is not
     * a measured value: skip it.
     *
     * @return whether a sample was added
     */
    private static boolean metricIfPresent(List<MetricData> out, String key,
            SimpleJson field) {
        Double value = ExampleValues.numericOrNull(field.asString());
        if (value == null) return false;
        metric(out, key, value);
        return true;
    }

    private static void metric(List<MetricData> out, String key, double value) {
        out.add(new MetricData(new MetricKey(key), System.currentTimeMillis(), value));
    }

    private static void prop(List<MetricData> out, String key, String value) {
        out.add(new MetricData(new MetricKey(true, key),
                System.currentTimeMillis(), value != null ? value : ""));
    }

    // -----------------------------------------------------------------------
    // onDiscard — release per-instance state. Always call super to close the
    // framework-managed HTTP client.
    // -----------------------------------------------------------------------

    @Override
    public void onDiscard() {
        this.api = null;
        super.onDiscard();
    }
}
