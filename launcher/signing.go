package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
)

// The statement an archive signature covers (docs/adr/0013-signed-archives.md). A verifier never reads this text from the registry:
// it builds it from what it actually observed, so a valid signature cannot be moved to another fingerprint, manifest, archive or
// repository. Java builds the same bytes (SignedStatement.java); launcher/testdata/signing/vector.json is the frozen contract both
// sides must reproduce.
const statementHeader = "mnemo-warmup-archive-v1"

type signedStatement struct {
	fingerprint string // "sha256:<64 hex>": what the archive was trained for
	manifest    string // "sha256:<64 hex>": the stored manifest, which is the OCI config blob
	archive     string // "sha256:<64 hex>": the archive, which is the OCI layer
	size        int64  // the archive's size in bytes
	repository  string // the repository path as the operator configured it, without the registry host
}

// text returns the exact bytes that are signed. It refuses a field that is not what it claims to be, which also means no field can
// contain a newline and change the framing.
func (s signedStatement) text() (string, error) {
	for _, f := range []struct{ name, value string }{{"fingerprint", s.fingerprint}, {"manifest", s.manifest}, {"archive", s.archive}} {
		if !digestRe.MatchString(f.value) {
			return "", fmt.Errorf("signed statement: %s is not a sha256 digest", f.name)
		}
	}
	if s.size <= 0 {
		return "", fmt.Errorf("signed statement: size must be positive")
	}
	if !repoRe.MatchString(s.repository) {
		return "", fmt.Errorf("signed statement: %q is not a repository path", s.repository)
	}
	return fmt.Sprintf("%s\nfingerprint: %s\nmanifest: %s\narchive: %s\nsize: %d\nrepository: %s\n",
		statementHeader, s.fingerprint, s.manifest, s.archive, s.size, s.repository), nil
}

// signingKeyID is the first 8 bytes of the SHA-256 of the raw public key, as 16 lower-case hex characters. It names a key in an
// archive's signature and in the list of public keys an operator gives a pod; Java derives the same id (SigningKey.keyId).
func signingKeyID(pub ed25519.PublicKey) string {
	sum := sha256.Sum256(pub)
	return hex.EncodeToString(sum[:8])
}
