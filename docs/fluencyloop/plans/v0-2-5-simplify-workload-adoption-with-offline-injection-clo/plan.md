# Plan: v0.2.5 adoption

started: 2026-11-10

## Goal & scope

Make a supported Kubernetes workload easy to adopt with a reviewable offline injector, cloud registry identity, and shell-free deployment. Provide conservative registry retention guidance.

Scope: offline Deployment injection and Helm post-rendering, one EKS/ECR identity path, a native distroless launcher, end-to-end validation, and adoption documentation. Preserve explicit overrides and fail-open cache behavior.

Non-goals: mutating webhook or controller, warden integration, GKE/AKS providers, and automatic archive deletion. The existing v0.3.0 milestone tracks later automation.

## Open questions

- Which implementation language and distribution model best meet native launcher size, portability and maintenance needs? Resolve during T3.
- How will real EKS/ECR integration tests obtain infrastructure and credentials? This affects the strength of cloud verification (T4/T6).
- How should the offline injector handle original commands supplied only by image metadata? The supported path and explicit overrides are defined in T1.

## Architecture

The offline injector consumes rendered Kubernetes resources, discovers supported application configuration, previews changes, and emits a Deployment and trainer resources. It must preserve unrelated resources and support safe reinjection and removal. A webhook/controller is deferred because its admission availability, TLS and reconciliation duties add operational complexity.

A native launcher supports installation, cache fetch and application execution without a shell. It retains the original command and enforces the same validation and fallback behavior as existing startup helpers.

A cloud credential provider obtains and refreshes registry credentials using workload identity. Reader and trainer identities can have different permissions. Existing static authentication remains available.

Registry lifecycle policies manage retention initially. Documentation explains scope, shared consumers, rollback and concurrent uploads; no automatic deletion is included.

## Task breakdown

| ID | Intent | Size | Depends on |
|---|---|---|---|
| T1 | Define injection contract | M | — |
| T2 | Implement offline injection and preview | L | T1 |
| T3 | Add shell-free launcher and fetch | L | T1 |
| T4 | Add EKS/ECR workload identity | L | — |
| T5 | Document safe registry retention | M | — |
| T6 | Validate the complete adoption path | L | T2, T3, T4 |
| T7 | Publish adoption guide and reference | M | T1–T6 |

## Roadmap & critical path

Start T1, T4 and T5 independently. Build T2 and T3 against the T1 contract. T6 validates their combined behavior with T4; T7 documents the verified result. The critical path is T1 → T2/T3 → T6 → T7: the integration and guide cannot be completed until both injection and the shell-free launch path are available. T4 is a parallel prerequisite for T6 and may become critical if cloud test infrastructure takes longer.

## Constitution check

§1 and §3 require cache and helper failures to preserve a working application start. Injection must surface unsupported commands before deployment; startup paths must fall back safely. §2 requires verification of fetched archive bytes before use. Workload identity must not weaken those guarantees.

## Tickets

- Milestone: https://github.com/baokhang83/mnemo-warmup/milestone/4
- T1: https://github.com/baokhang83/mnemo-warmup/issues/54
- T2: https://github.com/baokhang83/mnemo-warmup/issues/55
- T3: https://github.com/baokhang83/mnemo-warmup/issues/56
- T4: https://github.com/baokhang83/mnemo-warmup/issues/57
- T5: https://github.com/baokhang83/mnemo-warmup/issues/58
- T6: https://github.com/baokhang83/mnemo-warmup/issues/59
- T7: https://github.com/baokhang83/mnemo-warmup/issues/60
