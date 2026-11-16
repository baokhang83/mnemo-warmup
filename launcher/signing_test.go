package main

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"reflect"
	"strings"
	"testing"
)

// vector is launcher/testdata/signing/vector.json, the frozen contract the Java side (SignedStatementTest) reproduces too.
type signingVector struct {
	Seed      string `json:"seed"`
	PublicKey string `json:"publicKey"`
	KeyID     string `json:"keyId"`
	Statement struct {
		Fingerprint string `json:"fingerprint"`
		Manifest    string `json:"manifest"`
		Archive     string `json:"archive"`
		Size        int64  `json:"size"`
		Repository  string `json:"repository"`
	} `json:"statement"`
	PublicKeyEntry string `json:"publicKeyEntry"`
	Text           string `json:"text"`
	Signature      string `json:"signature"`
	SignatureFile  string `json:"signatureFile"`
}

func loadSigningVector(t *testing.T) (signingVector, signedStatement) {
	t.Helper()
	raw, err := os.ReadFile("testdata/signing/vector.json")
	if err != nil {
		t.Fatal(err)
	}
	var v signingVector
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	return v, signedStatement{v.Statement.Fingerprint, v.Statement.Manifest, v.Statement.Archive, v.Statement.Size, v.Statement.Repository}
}

func TestSignedStatementReproducesTheFrozenText(t *testing.T) {
	v, s := loadSigningVector(t)
	got, err := s.text()
	if err != nil {
		t.Fatal(err)
	}
	if got != v.Text {
		t.Fatalf("statement text differs from the frozen vector:\n got %q\nwant %q", got, v.Text)
	}
}

// The signature is part of the contract: Go's ed25519 must produce, and accept, exactly the bytes the vector holds (and Java's must too).
func TestFrozenSignatureIsReproducedAndVerifies(t *testing.T) {
	v, s := loadSigningVector(t)
	seed, _ := hex.DecodeString(v.Seed)
	wantPub, _ := hex.DecodeString(v.PublicKey)
	priv := ed25519.NewKeyFromSeed(seed)
	if !priv.Public().(ed25519.PublicKey).Equal(ed25519.PublicKey(wantPub)) {
		t.Fatal("the seed does not give the vector's public key")
	}
	text, _ := s.text()
	sig := ed25519.Sign(priv, []byte(text))
	if hex.EncodeToString(sig) != v.Signature {
		t.Fatalf("signature differs from the frozen vector: %x", sig)
	}
	if !ed25519.Verify(wantPub, []byte(text), sig) {
		t.Fatal("the frozen signature does not verify")
	}
}

// Changing any one field changes the signed text, so a signature made for one archive never verifies for another.
func TestEveryFieldChangesTheSignedBytes(t *testing.T) {
	v, base := loadSigningVector(t)
	seed, _ := hex.DecodeString(v.Seed)
	priv := ed25519.NewKeyFromSeed(seed)
	pub := priv.Public().(ed25519.PublicKey)
	baseText, _ := base.text()
	sig := ed25519.Sign(priv, []byte(baseText))
	other := "sha256:" + strings.Repeat("44", 32)
	variants := map[string]func(signedStatement) signedStatement{
		"fingerprint": func(s signedStatement) signedStatement { s.fingerprint = other; return s },
		"manifest":    func(s signedStatement) signedStatement { s.manifest = other; return s },
		"archive":     func(s signedStatement) signedStatement { s.archive = other; return s },
		"size":        func(s signedStatement) signedStatement { s.size++; return s },
		"repository":  func(s signedStatement) signedStatement { s.repository = "team/other"; return s },
	}
	for name, mutate := range variants {
		text, err := mutate(base).text()
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if text == baseText {
			t.Fatalf("%s does not change the signed text", name)
		}
		if ed25519.Verify(pub, []byte(text), sig) {
			t.Fatalf("a signature for the original statement verified after changing %s (a replay)", name)
		}
	}
}

func TestSignedStatementRefusesWhatIsNotAStatement(t *testing.T) {
	_, base := loadSigningVector(t)
	bad := map[string]func(signedStatement) signedStatement{
		"short digest":          func(s signedStatement) signedStatement { s.archive = "sha256:abcd"; return s },
		"upper-case digest":     func(s signedStatement) signedStatement { s.manifest = "sha256:" + strings.Repeat("AB", 32); return s },
		"no algorithm":          func(s signedStatement) signedStatement { s.fingerprint = strings.Repeat("11", 32); return s },
		"digest with a newline": func(s signedStatement) signedStatement { s.archive = s.archive + "\n"; return s },
		"zero size":             func(s signedStatement) signedStatement { s.size = 0; return s },
		"negative size":         func(s signedStatement) signedStatement { s.size = -1; return s },
		"empty repository":      func(s signedStatement) signedStatement { s.repository = ""; return s },
		"newline in repository": func(s signedStatement) signedStatement { s.repository = "team/app\nsize: 1"; return s },
		"trailing newline":      func(s signedStatement) signedStatement { s.repository = "team/app\n"; return s },
		"upper-case repository": func(s signedStatement) signedStatement { s.repository = "Team/App"; return s },
		"host in repository":    func(s signedStatement) signedStatement { s.repository = "registry:5000/team/app"; return s },
	}
	for name, mutate := range bad {
		if _, err := mutate(base).text(); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

// The key id is part of the contract: a signature names its key by this id, so both languages must derive the same one.
func TestKeyIDMatchesTheFrozenVector(t *testing.T) {
	v, _ := loadSigningVector(t)
	pub, _ := hex.DecodeString(v.PublicKey)
	if got := signingKeyID(pub); got != v.KeyID || len(got) != 16 {
		t.Fatalf("key id %q, want %q", got, v.KeyID)
	}
	other := make([]byte, 32)
	if signingKeyID(other) == v.KeyID {
		t.Fatal("different keys must not share an id")
	}
}

// ---- the signature file, trusted keys and verification: the Go twins of SignatureFile and ArchiveVerifier ----

// The file text is part of the contract: Java writes and reads the same bytes.
func TestSignatureFileMatchesTheFrozenVectorAndRoundTrips(t *testing.T) {
	v, _ := loadSigningVector(t)
	signature, _ := hex.DecodeString(v.Signature)
	text, ok := signatureFileText(v.KeyID, signature, v.Statement.Repository)
	if !ok || text != v.SignatureFile {
		t.Fatalf("signature file %q, want %q", text, v.SignatureFile)
	}
	keyID, parsed, repository, ok := parseSignatureFile(text)
	if !ok || keyID != v.KeyID || repository != v.Statement.Repository || !reflect.DeepEqual(parsed, signature) {
		t.Fatalf("round trip: %v %q %q", ok, keyID, repository)
	}
}

func TestSignatureFileRefusesAnythingThatIsNotExactlyTheFormat(t *testing.T) {
	v, _ := loadSigningVector(t)
	good := v.SignatureFile
	for name, bad := range map[string]string{
		"empty":                "",
		"not a signature file": "hello\n",
		"other version":        strings.Replace(good, "signature-v1", "signature-v2", 1),
		"no final newline":     strings.TrimSuffix(good, "\n"),
		"an extra line":        good + "extra: x\n",
		"a wrong field name":   strings.Replace(good, "keyid: ", "id: ", 1),
		"upper-case repo":      strings.Replace(good, "repository: team/app", "repository: Team/App", 1),
		"bad base64":           strings.Replace(good, "signature: ", "signature: !!!", 1),
		"short signature":      "mnemo-warmup-signature-v1\nkeyid: " + v.KeyID + "\nsignature: " + base64.StdEncoding.EncodeToString(make([]byte, 63)) + "\nrepository: team/app\n",
		"newline in repo":      strings.Replace(good, "repository: team/app", "repository: team/app\nsize: 1", 1),
		"carriage return":      strings.Replace(good, "\n", "\r\n", 1),
		"short key id":         strings.Replace(good, "keyid: "+v.KeyID, "keyid: abcd", 1),
	} {
		if _, _, _, ok := parseSignatureFile(bad); ok {
			t.Errorf("%s: accepted", name)
		}
	}
	if _, ok := signatureFileText(v.KeyID, make([]byte, 64), "bad\nrepo"); ok {
		t.Error("a repository with a newline was written")
	}
}

func TestSignatureKeysAreParsedLikeJavaDoes(t *testing.T) {
	v, _ := loadSigningVector(t)
	other := make([]byte, 32)
	other[0] = 7
	var notes []string
	note := func(m string) { notes = append(notes, m) }
	entries := strings.Join([]string{
		"justtext",
		"abcd=!!!notbase64",
		"abcd=" + base64.StdEncoding.EncodeToString(make([]byte, 31)),
		"0000000000000000=" + base64.StdEncoding.EncodeToString(other), // an id that is not that key's id
		"",
		v.PublicKeyEntry,
	}, ",")
	keys := parseSignatureKeys(entries, note)
	if len(notes) != 4 {
		t.Fatalf("notes %q", notes)
	}
	if len(keys) != 1 || keys[v.KeyID] == nil {
		t.Fatalf("only the good entry may survive: %v", keys)
	}
	if got := parseSignatureKeys("", note); len(got) != 0 {
		t.Fatal("an empty list has no keys")
	}
}

func TestVerifyDistinguishesValidUnknownAndBad(t *testing.T) {
	v, s := loadSigningVector(t)
	signature, _ := hex.DecodeString(v.Signature)
	keys := parseSignatureKeys(v.PublicKeyEntry, func(string) {})
	if got := keys.verify(s, v.KeyID, signature); got != verifyValid {
		t.Fatalf("the frozen signature must verify, got %v", got)
	}
	if got := keys.verify(s, "0000000000000000", signature); got != verifyUnknownKey {
		t.Fatalf("an unlisted key id, got %v", got)
	}
	flipped := append([]byte{}, signature...)
	flipped[5] ^= 1
	if got := keys.verify(s, v.KeyID, flipped); got != verifyBadSignature {
		t.Fatalf("a flipped bit, got %v", got)
	}
	if got := keys.verify(s, v.KeyID, signature[:63]); got != verifyBadSignature {
		t.Fatalf("a short signature, got %v", got)
	}
	replay := s
	replay.repository = "team/orders" // the design's example: the same signature does not cover another repository
	if got := keys.verify(replay, v.KeyID, signature); got != verifyBadSignature {
		t.Fatalf("a replay into another repository, got %v", got)
	}
	bad := s
	bad.size = 0 // not even a statement
	if got := keys.verify(bad, v.KeyID, signature); got != verifyBadSignature {
		t.Fatalf("an invalid statement, got %v", got)
	}
	if keys.verify(s, v.KeyID, signature).problem(v.KeyID) != "" || verifyUnknownKey.problem("abc") == "" || verifyBadSignature.problem("abc") == "" {
		t.Fatal("problem() must be empty for valid and set otherwise")
	}
}
