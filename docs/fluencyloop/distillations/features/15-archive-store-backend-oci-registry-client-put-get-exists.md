# OCI archive store backend

**Before:** archives existed only on the trainer's disk, and no code could move them between pods.
**After:** an archive and its manifest can be stored in and fetched from an OCI registry, keyed by
fingerprint. A new `push` command uploads what `build` produced. Every download is checked against
its content digest and discarded on mismatch ([[§2]]). Credentials come from a mounted Docker-config
file with separate pull and push logins. Any store problem becomes an error the caller treats as
"no archive" ([[§1]]). Fetching in the init container (`fetch`) is the next step.
