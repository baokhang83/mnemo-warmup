# Archive store

A trained archive is made once per fingerprint and needed by every pod. The archive store is the
shared place between them. It is a registry holding two pieces per fingerprint: the manifest that
says what the archive was trained against, and the archive itself. Both are named by a digest,
which is a hash of their bytes. A tag made from the fingerprint finds the pair. A reader hashes
what it downloads and compares it with the digest. If anything differs, or the store cannot be
reached or refuses the login, the result is "no archive" and the application starts without one.
