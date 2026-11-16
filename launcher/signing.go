package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"regexp"
	"strings"
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

// ---- trusted keys, verification and the files a fetch leaves behind (the Go twin of ArchiveVerifier and SignatureFile) ----

const (
	// envSigningPublicKeys lists the public keys a pod trusts, as "id=base64,id=base64". Setting it makes a valid signature mandatory.
	envSigningPublicKeys = "MNEMO_WARMUP_SIGNING_PUBLIC_KEYS"
	signatureFile        = "mnemo-warmup.signature"
	signatureFileHeader  = "mnemo-warmup-signature-v1"
	// the two annotations the trainer puts on the OCI manifest
	annotationSignature = "io.github.baokhang83.mnemo.warmup.signature"
	annotationKeyID     = "io.github.baokhang83.mnemo.warmup.keyid"
)

var keyIDRe = regexp.MustCompile(`^[0-9a-f]{16}$`)

// smallOrderY are the y-coordinates (little-endian) of the Ed25519 points of order 1, 2, 4 and 8. A public key that is one of them makes
// verification succeed for every message with a trivial signature, so no honest key looks like this. Java refuses the same set
// (SigningKey.weakness). They were computed from the curve equation, not copied.
var smallOrderY = []string{
	"0000000000000000000000000000000000000000000000000000000000000000",
	"0100000000000000000000000000000000000000000000000000000000000000",
	"ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f",
	"26e8958fc2b227b045c3f489f2ef98f0d5dfac05d3c63339b13802886d53fc05",
	"c7176a703d4dd84fba3c0b760d10670f2a2053fa2c39ccc64ec7fd7792ac037a",
}

// publicKeyWeakness says why a public key must never be trusted ("" when it may be). The top bit of the last byte is only the sign of x,
// so it is ignored; a y at or above p = 2^255-19 is a second spelling of a smaller y, and Go and the JDK treat those differently.
func publicKeyWeakness(key []byte) string {
	if len(key) != ed25519.PublicKeySize {
		return "an Ed25519 public key is 32 bytes"
	}
	y := append([]byte(nil), key...)
	y[31] &= 0x7f
	if y[31] == 0x7f && y[0] >= 0xed {
		canonical := false
		for _, b := range y[1:31] {
			if b != 0xff {
				canonical = true
				break
			}
		}
		if !canonical {
			return "its encoding is not canonical"
		}
	}
	for _, bad := range smallOrderY {
		if hex.EncodeToString(y) == bad {
			return "it is a small-order point, for which any message verifies"
		}
	}
	return ""
}

type signatureKeys map[string]ed25519.PublicKey

// parseSignatureKeys reads "id=base64,id=base64". An entry that is malformed, or whose id is not the id of its own key, is skipped with
// a note: a typo must never silently trust the wrong key, and must never stop an application. The rules and the words match Java's.
func parseSignatureKeys(entries string, note func(string)) signatureKeys {
	keys := signatureKeys{}
	for _, entry := range strings.Split(entries, ",") {
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		id, encoded, found := strings.Cut(entry, "=")
		if !found || id == "" {
			note("ignoring a signing public key entry that is not id=base64")
			continue
		}
		raw, err := base64.StdEncoding.DecodeString(encoded)
		switch {
		case err != nil:
			note("ignoring signing public key " + id + ": not valid base64")
		case publicKeyWeakness(raw) != "":
			note("ignoring signing public key " + id + ": " + publicKeyWeakness(raw))
		case signingKeyID(raw) != id:
			note("ignoring signing public key " + id + ": the id is not the id of that key (expected " + signingKeyID(raw) + ")")
		default:
			keys[id] = ed25519.PublicKey(raw)
		}
	}
	return keys
}

type verifyResult int

const (
	verifyValid        verifyResult = iota // a listed key signed exactly this statement
	verifyUnknownKey                       // the signature names a key that is not listed
	verifyBadSignature                     // the key is listed but the signature does not match the statement
)

func (k signatureKeys) verify(s signedStatement, keyID string, signature []byte) verifyResult {
	pub, ok := k[keyID]
	if !ok {
		return verifyUnknownKey
	}
	text, err := s.text()
	if err != nil || len(signature) != ed25519.SignatureSize || !ed25519.Verify(pub, []byte(text), signature) {
		return verifyBadSignature
	}
	return verifyValid
}

// problem is why a result is not usable, in the words Java uses ("" when valid).
func (r verifyResult) problem(keyID string) string {
	switch r {
	case verifyUnknownKey:
		return "signed by a key that is not trusted (" + keyID + ")"
	case verifyBadSignature:
		return "carrying a signature that does not match it"
	}
	return ""
}

// signatureFileText is the text of mnemo-warmup.signature. It holds no statement: the checker rebuilds that from the files and the
// repository named here, so the file cannot lie about what was signed. Java writes and reads the same bytes (SignatureFile).
func signatureFileText(keyID string, signature []byte, repository string) (string, bool) {
	if !keyIDRe.MatchString(keyID) || len(signature) != ed25519.SignatureSize || !repoRe.MatchString(repository) {
		return "", false
	}
	return fmt.Sprintf("%s\nkeyid: %s\nsignature: %s\nrepository: %s\n", signatureFileHeader, keyID, base64.StdEncoding.EncodeToString(signature), repository), true
}

// parseSignatureFile accepts exactly what signatureFileText writes.
func parseSignatureFile(text string) (keyID string, signature []byte, repository string, ok bool) {
	if !strings.HasSuffix(text, "\n") || strings.Contains(text, "\r") {
		return "", nil, "", false
	}
	lines := strings.Split(strings.TrimSuffix(text, "\n"), "\n")
	if len(lines) != 4 || lines[0] != signatureFileHeader {
		return "", nil, "", false
	}
	field := func(line, name string) (string, bool) { return strings.CutPrefix(line, name+": ") }
	keyID, ok1 := field(lines[1], "keyid")
	encoded, ok2 := field(lines[2], "signature")
	repository, ok3 := field(lines[3], "repository")
	if !ok1 || !ok2 || !ok3 {
		return "", nil, "", false
	}
	signature, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return "", nil, "", false
	}
	if _, ok := signatureFileText(keyID, signature, repository); !ok {
		return "", nil, "", false
	}
	return keyID, signature, repository, true
}

// storedSignature reads the signature the trainer put in the manifest's annotations, or returns false when it is absent or malformed
// (which is the same as unsigned).
func storedSignature(annotations map[string]string) (keyID string, signature []byte, ok bool) {
	keyID, haveID := annotations[annotationKeyID]
	encoded, haveSig := annotations[annotationSignature]
	if !haveID || !haveSig || !keyIDRe.MatchString(keyID) {
		return "", nil, false
	}
	signature, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil || len(signature) != ed25519.SignatureSize {
		return "", nil, false
	}
	return keyID, signature, true
}
