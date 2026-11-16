package main

import (
	"crypto/ed25519"
	"encoding/hex"
	"encoding/json"
	"os"
	"strings"
	"testing"
)

// vector is launcher/testdata/signing/vector.json, the frozen contract the Java side (SignedStatementTest) reproduces too.
type signingVector struct {
	Seed      string `json:"seed"`
	PublicKey string `json:"publicKey"`
	Statement struct {
		Fingerprint string `json:"fingerprint"`
		Manifest    string `json:"manifest"`
		Archive     string `json:"archive"`
		Size        int64  `json:"size"`
		Repository  string `json:"repository"`
	} `json:"statement"`
	Text      string `json:"text"`
	Signature string `json:"signature"`
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
