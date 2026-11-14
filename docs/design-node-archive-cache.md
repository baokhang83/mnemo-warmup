# Node-local archive cache: design investigation

Status: draft. This document identifies the design and implementation work needed before a node-local cache can be offered. Issue #92 tracks the subsequent benchmark.

## Problem and scope

The current injector creates a pod-local `emptyDir`; each pod independently downloads an archive from the OCI registry. A node-local cache could reuse archive bytes across pods on the same node and avoid repeated downloads during a burst. It would introduce an optional shared node component, local disk use, a cache lifecycle and a new trust boundary. The existing per-pod path must remain the default.

The registry remains the source of truth for archive metadata and integrity. A cache hit must never make an archive usable by a workload that is not authorized to access it. A cache that simply serves blobs to every pod on a node, or shares one workload's credentials with others, does not meet this requirement.

## Proposed first release boundary

Investigate an opt-in, node-local cache deployed by an operator for a clearly defined trust domain. Each pod must reach the cache on its own node; node affinity and routing must be verified rather than assumed. A cache request must be authorized for the requesting workload and the registry/repository, including on a cache hit. The first implementation must specify and enforce how caller identity maps to that permission. Until that mechanism is implemented and reviewed, a public node endpoint or unrestricted `hostPath` is not an acceptable general solution.

The fetcher should retain its existing registry authentication and archive verification. A cache should hold content-addressed data, keyed by registry/repository and verified content digest, not merely a fingerprint tag: registry tags may be repaired or changed. Each consumer must validate the manifest, archive digest and compatibility before use. Cache failure or timeout should fall back to the existing direct registry fetch; registry failure should retain the existing uncached JVM fallback. Both paths need bounded waiting.

## Required behavior

- Concurrent requests for the same content coordinate so that one download populates an entry while other callers wait for a completed, verified result. Waiting and lock recovery must be bounded.
- Downloads go to temporary files and become visible atomically only after complete verification. A crash, cancellation, disk-full condition or corrupt entry must not expose partial content.
- The operator configures a storage limit and eviction policy. Eviction must not break a pod that is using an archive; node loss and cache restarts are treated as misses.
- Cache hits, misses, duplicate-download suppression, errors, bytes and timings are observable without exposing registry credentials or sensitive metadata. Credentials must not be logged or persisted in the cache.
- An absent or unhealthy cache must not make the existing pod path dependent on a node service. Deployment configuration, required permissions and uninstall/cleanup need explicit documentation.

## Decisions and validation before implementation

1. Select the caller authentication and authorization mechanism and define isolation between namespaces, service accounts, registries and repositories. Verify that registry authorization cannot be bypassed through a cache hit.
2. Select node-local routing and storage with an explicit Kubernetes and runtime support matrix, permissions and resource limits. Evaluate whether a DaemonSet and local endpoint are justified against simpler alternatives.
3. Specify cold starts, warm hits, concurrent misses, stale or repaired tags, registry unavailability, service restarts, timeouts, eviction, corruption and disk exhaustion for both native and Java fetch paths.
4. Build an opt-in implementation and exercise concurrent fetches, access isolation and failure recovery. Compare one and nine concurrent downloads with and without the cache, collecting pod phases, bytes, cache outcomes and CPU. Repeat the experiment in a same-region cloud environment when available.

The present GKE study demonstrates nine per-pod downloads in one environment; it does not establish a benefit for any shared-cache design.
