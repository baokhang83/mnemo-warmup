# Trainer and Helm chart

**Before:** archives could be trained, stored, fetched and used from the command line, but nothing ran
the training inside a cluster, and the plan left the chart open.
**After:** a scheduled job in the application's own image runs `mnemo-warmup train`. It skips work when
the store already has the archive, takes a lease named after the fingerprint so only one trainer works
on it, trains, uploads, and reports what happened; it never affects a release or a starting pod
([[§1]], [[docs/adr/0005-trainer-and-chart.md]]). The Helm chart owns the trainer and its minimal
permissions, and offers reusable pieces that the application's own chart adds to its pod template,
built from the same definition the trainer uses so both describe the launch the same way.
