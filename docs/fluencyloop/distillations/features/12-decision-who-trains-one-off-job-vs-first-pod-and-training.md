# Training trigger and traffic decision

**Before:** it was open who produces the archive and with what workload.
**After:** a scheduled job from the chart trains when the store lacks the archive for this image.
It works out the fingerprint itself from the image, takes a short-lived lock named after it so
two releases cannot train at once, and always ends successfully so a release never fails because
of the cache. Training drives a normal Spring startup by default; probing chosen URLs is opt-in.
Pods that start before the archive exists run without it (Constitution §1). The first draft used
a release hook and was changed after review. See `docs/adr/0002-training-trigger-and-traffic.md`.
