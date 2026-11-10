# Training trigger and traffic decision

**Before:** it was open who produces the archive and with what workload.
**After:** a Job named from the fingerprint, created by the Helm chart, trains once per
fingerprint. It checks the store first, so repeats are harmless. Training drives a normal Spring
startup by default; probing chosen URLs is opt-in. Pods that start before the archive exists run
without it (Constitution §1). Recorded in `docs/adr/0002-training-trigger-and-traffic.md`.
