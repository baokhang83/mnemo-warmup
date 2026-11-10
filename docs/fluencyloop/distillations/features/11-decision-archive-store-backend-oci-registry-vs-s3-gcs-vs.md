# Archive store backend decision

**Before:** the plan left open where trained archives live between the trainer and the pods.
**After:** archives live in an OCI registry, tagged by fingerprint and verified by digest. The
choice is recorded in `docs/adr/0001-archive-store.md`. Access goes through a small
put/get/exists interface, so another backend can follow later. A store failure means no archive,
and the pod starts uncached (Constitution §1).

This unblocks the `fetch` command, the trainer upload, and the training-trigger decision.
