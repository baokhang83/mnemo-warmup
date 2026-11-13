# Spawned process shutdown

Previously, `run --spawn` could finish its shutdown hook before a newly started child was published. The child could miss SIGTERM and lose its graceful shutdown period.

The wrapper now waits for the child start result before it forwards SIGTERM. Both the wait for the start result and the wait for child shutdown are bounded. The restored end-to-end test checks that the wrapper waits for the child; a focused test covers delayed publication. [[warmup-cli/src/main/java/io/github/baokhang83/mnemo/warmup/cli/Main.java]]
