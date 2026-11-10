# Archive store

A trained archive is made once per fingerprint and needed by every pod. The archive store is the
shared place between them. A registry stores byte blobs by their SHA-256 digest and gives a
readable tag to each set of blobs. Here the tag is the fingerprint, so a pod finds its archive by
tag and checks the bytes against the digest. If the lookup, download, or check fails, the result
is "no archive" and the application starts without one.
