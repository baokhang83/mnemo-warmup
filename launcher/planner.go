package main

import (
	"archive/zip"
	"bufio"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// The native run planner (docs/adr/0010). It makes the same decision as the Java RunPlanner for the one case that matters on a
// pod start: the archive fits this exact launch. It answers "use it" only when every check the Java planner makes passes, and
// otherwise reports "not decided" so the launcher asks the Java planner, which stays the authority for the reasons it logs.
// A mistake here can therefore cost the speed-up but not start an application with a wrong archive (constitution §1, §2, §3):
// the archive is hashed before it is handed to the JVM, exactly as the Java planner does.

const (
	flagsFile    = "mnemo-warmup.flags"
	manifestFile = "mnemo-warmup.manifest.json"
	schemaV1     = 1
)

// planResult is what the native planner decided.
type planResult struct {
	argv    []string // the command to start
	notes   []string // lines for the log, printed with the "run: " prefix by the caller
	decided bool     // false: ask the Java planner
}

type manifestJSON struct {
	SchemaVersion int64  `json:"schemaVersion"`
	Fingerprint   string `json:"fingerprint"`
	CacheType     string `json:"cacheType"`
	JDK           struct {
		Vendor         string `json:"vendor"`
		Version        string `json:"version"`
		RuntimeVersion string `json:"runtimeVersion"`
		FeatureVersion int64  `json:"featureVersion"`
	} `json:"jdk"`
	ImageDigest *string    `json:"imageDigest"`
	JvmFlags    []string   `json:"jvmFlags"`
	Classpath   []cpEntry  `json:"classpath"`
	LaunchDir   *string    `json:"launchDir"`
	Archive     *archiveMD `json:"archive"`
}

type cpEntry struct {
	Name         string  `json:"name"`
	SizeBytes    int64   `json:"sizeBytes"`
	SHA256       string  `json:"sha256"`
	LastModified *string `json:"lastModified"`
}

type archiveMD struct {
	File      string `json:"file"`
	SizeBytes int64  `json:"sizeBytes"`
	SHA256    string `json:"sha256"`
}

// timer collects phase times in the same format as the Java PhaseTimer, so the benchmark summary reads both.
type timer struct {
	on    bool
	label string
	last  time.Time
	parts []string
}

func newTimer(on bool, label string) *timer { return &timer{on: on, label: label, last: time.Now()} }

func (t *timer) lap(phase string) {
	if !t.on {
		return
	}
	now := time.Now()
	t.parts = append(t.parts, fmt.Sprintf("%s=%d ms", phase, now.Sub(t.last).Milliseconds()))
	t.last = now
}

func (t *timer) summary() string {
	if !t.on {
		return ""
	}
	return "timing: " + strings.Join(t.parts, ", ") + ", tool-jvm-uptime=0 ms (" + t.label + ")"
}

// nativePlan decides how to start cmd. environ is the process environment as KEY=VALUE strings.
func nativePlan(cmd []string, environ []string) planResult {
	env := envMap(environ)
	t := newTimer(env["MNEMO_WARMUP_TIMING"] == "1", "native planner")
	res, ok := decide(cmd, env, t)
	if !ok {
		return planResult{}
	}
	if s := t.summary(); s != "" {
		res.notes = append(res.notes, s)
	}
	res.decided = true
	return res
}

func envMap(environ []string) map[string]string {
	m := make(map[string]string, len(environ))
	for _, kv := range environ {
		if i := strings.IndexByte(kv, '='); i > 0 {
			m[kv[:i]] = kv[i+1:]
		}
	}
	return m
}

func decide(cmd []string, env map[string]string, t *timer) (planResult, bool) {
	plain := planResult{argv: cmd}
	cacheDir := env["MNEMO_WARMUP_CACHE_DIR"]
	if cacheDir == "" {
		cacheDir = ".mnemo-warmup/cache"
	}
	imageDigest := strings.TrimSpace(env["MNEMO_WARMUP_IMAGE_DIGEST"])

	flagsBytes, err := os.ReadFile(filepath.Join(cacheDir, flagsFile))
	if err != nil || strings.TrimSpace(string(flagsBytes)) == "" {
		// Nothing was fetched: the Java planner says exactly this, so it need not start a JVM to say it.
		plain.notes = []string{"no archive was fetched into " + cacheDir + "; starting without one"}
		return plain, true
	}
	flag := strings.TrimSpace(string(flagsBytes))
	if strings.ContainsAny(flag, " \n") || !strings.Contains(flag, "=") {
		return planResult{}, false
	}
	manifestBytes, err := os.ReadFile(filepath.Join(cacheDir, manifestFile))
	if err != nil {
		return planResult{}, false
	}
	m, ok := parseManifest(manifestBytes)
	if !ok {
		return planResult{}, false
	}
	t.lap("read-manifest")

	archivePath := flag[strings.IndexByte(flag, '=')+1:]
	if flag != runtimeFlag(m.CacheType, archivePath) || m.Archive == nil {
		return planResult{}, false
	}
	info, err := os.Stat(archivePath)
	if err != nil || !info.Mode().IsRegular() || info.Size() != m.Archive.SizeBytes {
		return planResult{}, false
	}

	launch, ok := parseJavaLaunch(cmd)
	if !ok {
		return planResult{}, false
	}
	for _, arg := range cmd {
		for _, opt := range archiveOptions {
			if strings.HasPrefix(arg, opt) {
				return planResult{}, false
			}
		}
	}
	if fat, ok := isFatJar(launch.jar); !ok || fat {
		return planResult{}, false
	}
	jar, err := filepath.Abs(launch.jar)
	if err != nil {
		return planResult{}, false
	}

	// Verify before use (§2): hash the bytes that are about to be handed to the JVM, and the classpath, at the same time.
	var wg sync.WaitGroup
	var archiveSum string
	var archiveErr error
	wg.Add(1)
	go func() {
		defer wg.Done()
		archiveSum, archiveErr = sha256File(archivePath)
	}()
	files, ok := classpathFiles(jar)
	var live []cpEntry
	if ok {
		live, ok = describeClasspath(jar, files)
	}
	wg.Wait()
	t.lap("archive-sha256-and-classpath-hash")
	if !ok || archiveErr != nil || archiveSum != m.Archive.SHA256 {
		return planResult{}, false
	}

	home, ok := launch.javaHome(env)
	if !ok {
		return planResult{}, false
	}
	rel, ok := readRelease(home)
	t.lap("jdk-release")
	if !ok || rel.vendor != m.JDK.Vendor || rel.runtime != m.JDK.RuntimeVersion {
		return planResult{}, false // the Java planner probes the real JVM and decides
	}
	if (m.ImageDigest == nil && imageDigest != "") || (m.ImageDigest != nil && *m.ImageDigest != imageDigest) {
		return planResult{}, false
	}
	if !equalStrings(m.JvmFlags, relevantJvmFlags(launch.jvmOptions, env)) {
		return planResult{}, false
	}
	if !classpathMatches(m.Classpath, live) {
		return planResult{}, false
	}
	// The JVM records paths and compares modification times to the second (ADR 0003).
	if m.LaunchDir == nil || *m.LaunchDir != filepath.Dir(jar) {
		return planResult{}, false
	}
	for i := range m.Classpath {
		if !sameSecond(m.Classpath[i].LastModified, live[i].LastModified) {
			return planResult{}, false
		}
	}

	argv := append([]string{cmd[0], flag}, cmd[1:]...)
	return planResult{argv: argv, notes: []string{"using " + m.CacheType + " archive " + archivePath}}, true
}

var archiveOptions = []string{"-XX:SharedArchiveFile=", "-XX:AOTCache=", "-XX:ArchiveClassesAtExit=", "-XX:AOTCacheOutput="}

func runtimeFlag(cacheType, archivePath string) string {
	switch cacheType {
	case "APP_CDS":
		return "-XX:SharedArchiveFile=" + archivePath
	case "LEYDEN_AOT":
		return "-XX:AOTCache=" + archivePath
	}
	return "\x00unknown"
}

// ---- the manifest and its fingerprint ----

func parseManifest(b []byte) (manifestJSON, bool) {
	var m manifestJSON
	dec := json.NewDecoder(strings.NewReader(string(b)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&m); err != nil {
		// Unknown fields are refused on purpose: a manifest written by a newer tool is for the Java planner to judge.
		return m, false
	}
	if m.SchemaVersion != schemaV1 || (m.CacheType != "APP_CDS" && m.CacheType != "LEYDEN_AOT") {
		return m, false
	}
	if fingerprint(m) != m.Fingerprint {
		return m, false
	}
	return m, true
}

// fingerprint is "sha256:" + SHA-256 of the compact JSON that ArchiveManifest.fingerprint() builds in Java.
func fingerprint(m manifestJSON) string {
	var sb strings.Builder
	sb.WriteString(`{"schema":`)
	sb.WriteString(strconv.FormatInt(m.SchemaVersion, 10))
	sb.WriteString(`,"cacheType":`)
	quoteJSON(&sb, m.CacheType)
	sb.WriteString(`,"jdkVendor":`)
	quoteJSON(&sb, m.JDK.Vendor)
	sb.WriteString(`,"jdkRuntimeVersion":`)
	quoteJSON(&sb, m.JDK.RuntimeVersion)
	sb.WriteString(`,"jdkFeatureVersion":`)
	sb.WriteString(strconv.FormatInt(m.JDK.FeatureVersion, 10))
	sb.WriteString(`,"imageDigest":`)
	if m.ImageDigest == nil {
		sb.WriteString("null")
	} else {
		quoteJSON(&sb, *m.ImageDigest)
	}
	sb.WriteString(`,"jvmFlags":[`)
	for i, f := range m.JvmFlags {
		if i > 0 {
			sb.WriteByte(',')
		}
		quoteJSON(&sb, f)
	}
	sb.WriteString(`],"classpath":[`)
	for i, e := range m.Classpath {
		if i > 0 {
			sb.WriteByte(',')
		}
		sb.WriteByte('[')
		quoteJSON(&sb, e.Name)
		sb.WriteByte(',')
		sb.WriteString(strconv.FormatInt(e.SizeBytes, 10))
		sb.WriteByte(',')
		quoteJSON(&sb, e.SHA256)
		sb.WriteByte(']')
	}
	sb.WriteString(`]}`)
	sum := sha256.Sum256([]byte(sb.String()))
	return "sha256:" + hex.EncodeToString(sum[:])
}

// quoteJSON escapes a string exactly as the Java Json.quote does: the named escapes, \u00xx for other control characters, and
// every other character as it is (no escaping of non-ASCII, "/" or the DEL character).
func quoteJSON(sb *strings.Builder, s string) {
	sb.WriteByte('"')
	for _, r := range s {
		switch r {
		case '"':
			sb.WriteString(`\"`)
		case '\\':
			sb.WriteString(`\\`)
		case '\n':
			sb.WriteString(`\n`)
		case '\r':
			sb.WriteString(`\r`)
		case '\t':
			sb.WriteString(`\t`)
		case '\b':
			sb.WriteString(`\b`)
		case '\f':
			sb.WriteString(`\f`)
		default:
			if r < 0x20 {
				fmt.Fprintf(sb, `\u%04x`, r)
			} else {
				sb.WriteRune(r)
			}
		}
	}
	sb.WriteByte('"')
}

// ---- the launch command ----

type javaLaunch struct {
	executable string
	jvmOptions []string
	jar        string
}

// parseJavaLaunch accepts `<java> [-options] -jar <jar> [args]` only, like the Java planner.
func parseJavaLaunch(cmd []string) (javaLaunch, bool) {
	if len(cmd) < 3 {
		return javaLaunch{}, false
	}
	if base := filepath.Base(cmd[0]); base != "java" && base != "java.exe" {
		return javaLaunch{}, false
	}
	var options []string
	for i := 1; i < len(cmd); i++ {
		arg := cmd[i]
		if arg == "-jar" {
			if i+1 < len(cmd) {
				return javaLaunch{executable: cmd[0], jvmOptions: options, jar: cmd[i+1]}, true
			}
			return javaLaunch{}, false
		}
		if !strings.HasPrefix(arg, "-") || arg == "-cp" || arg == "-classpath" || arg == "--class-path" {
			return javaLaunch{}, false
		}
		options = append(options, arg)
	}
	return javaLaunch{}, false
}

// javaHome finds the JDK of the launched executable: its path if it has one, else the first executable on PATH, else JAVA_HOME.
func (l javaLaunch) javaHome(env map[string]string) (string, bool) {
	var bin string
	if strings.ContainsAny(l.executable, `/\`) {
		bin = l.executable
	} else {
		for _, dir := range filepath.SplitList(env["PATH"]) {
			if dir == "" {
				dir = "."
			}
			cand := filepath.Join(dir, l.executable)
			if st, err := os.Stat(cand); err == nil && st.Mode().IsRegular() && st.Mode()&0o111 != 0 {
				bin = cand
				break
			}
		}
	}
	if bin != "" {
		if real, err := filepath.EvalSymlinks(bin); err == nil {
			home := filepath.Dir(filepath.Dir(real))
			if st, err := os.Stat(filepath.Join(home, "bin", filepath.Base(real))); err == nil && st.Mode().IsRegular() {
				return home, true
			}
		}
	}
	if h := env["JAVA_HOME"]; strings.TrimSpace(h) != "" {
		return h, true
	}
	return "", false
}

// jdkRelease is what a JDK's `release` file says about it.
type jdkRelease struct {
	vendor, runtime, version string
}

// readRelease reads the vendor, full build and version of the JDK from its `release` file instead of starting it. Any doubt is
// a "not decided": the Java tool then probes the real JVM.
func readRelease(home string) (jdkRelease, bool) {
	f, err := os.Open(filepath.Join(home, "release"))
	if err != nil {
		return jdkRelease{}, false
	}
	defer f.Close()
	props := map[string]string{}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		k, v, found := strings.Cut(sc.Text(), "=")
		if found {
			props[strings.TrimSpace(k)] = strings.Trim(strings.TrimSpace(v), `"`)
		}
	}
	r := jdkRelease{vendor: props["IMPLEMENTOR"], runtime: props["JAVA_RUNTIME_VERSION"], version: props["JAVA_VERSION"]}
	return r, r.vendor != "" && r.runtime != "" && r.version != ""
}

// featureVersion extracts the feature release as JdkInfo.parseFeatureVersion does: 21.0.5 -> 21, 25-ea -> 25, 1.8.0_292 -> 8.
func featureVersion(version string) (int, bool) {
	m := regexp.MustCompile(`^(\d+)(?:\.(\d+))?`).FindStringSubmatch(strings.TrimSpace(version))
	if m == nil {
		return 0, false
	}
	major, err := strconv.Atoi(m[1])
	if err != nil {
		return 0, false
	}
	if major == 1 && m[2] != "" {
		minor, err := strconv.Atoi(m[2])
		return minor, err == nil
	}
	return major, true
}

var relevantFlag = []*regexp.Regexp{
	regexp.MustCompile(`^-Xm[sx]\S+$`),
	regexp.MustCompile(`^-XX:[+-]Use\w*GC$`),
	regexp.MustCompile(`^-XX:(Max|Min|Initial)RAMPercentage=\S+$`),
	regexp.MustCompile(`^-XX:(MaxHeapSize|MinHeapSize|InitialHeapSize|SoftMaxHeapSize)=\S+$`),
	regexp.MustCompile(`^-XX:(ObjectAlignmentInBytes|CompressedClassSpaceSize)=\S+$`),
	regexp.MustCompile(`^-XX:[+-]UseCompressed(Oops|ClassPointers)$`),
	regexp.MustCompile(`^-XX:[+-]UseCompactObjectHeaders$`),
}

// relevantJvmFlags mirrors JvmFlags.relevant: the options that can make an archive unusable, the last occurrence of each winning,
// sorted by key, including those the JVM reads from JDK_JAVA_OPTIONS and JAVA_TOOL_OPTIONS.
func relevantJvmFlags(jvmArgs []string, env map[string]string) []string {
	all := append([]string{}, jvmArgs...)
	for _, name := range []string{"JDK_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS"} {
		all = append(all, strings.Fields(env[name])...)
	}
	byKey := map[string]string{}
	for _, arg := range all {
		for _, re := range relevantFlag {
			if re.MatchString(arg) {
				byKey[flagKey(arg)] = arg
				break
			}
		}
	}
	keys := make([]string, 0, len(byKey))
	for k := range byKey {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]string, 0, len(keys))
	for _, k := range keys {
		out = append(out, byKey[k])
	}
	return out
}

func flagKey(arg string) string {
	if strings.HasPrefix(arg, "-Xmx") || strings.HasPrefix(arg, "-Xms") {
		return arg[:4]
	}
	name := strings.TrimPrefix(arg, "-XX:")
	if strings.HasPrefix(name, "+") || strings.HasPrefix(name, "-") {
		return "-XX:" + name[1:]
	}
	if i := strings.IndexByte(name, '='); i >= 0 {
		return "-XX:" + name[:i]
	}
	return "-XX:" + name
}

// ---- JARs ----

// jarManifest returns the main attributes of the JAR's META-INF/MANIFEST.MF, keys lower-cased (JAR attribute names are
// case-insensitive). A JAR without a manifest has none.
func jarManifest(jar string) (map[string]string, bool) {
	zr, err := zip.OpenReader(jar)
	if err != nil {
		return nil, false
	}
	defer zr.Close()
	for _, f := range zr.File {
		if !strings.EqualFold(f.Name, "META-INF/MANIFEST.MF") {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			return nil, false
		}
		defer rc.Close()
		data, err := io.ReadAll(io.LimitReader(rc, 1<<20))
		if err != nil {
			return nil, false
		}
		return parseMainAttributes(string(data)), true
	}
	return map[string]string{}, true
}

func parseMainAttributes(text string) map[string]string {
	attrs := map[string]string{}
	var key string
	for _, line := range strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n") {
		if line == "" {
			break // the main section ends at the first blank line
		}
		if line[0] == ' ' && key != "" {
			attrs[key] += line[1:]
			continue
		}
		k, v, ok := strings.Cut(line, ": ")
		if !ok {
			key = ""
			continue
		}
		key = strings.ToLower(k)
		attrs[key] = v
	}
	return attrs
}

// isFatJar mirrors JarExtractor.needsExtraction: a Spring Boot executable JAR that must be extracted first.
func isFatJar(jar string) (fat, ok bool) {
	attrs, ok := jarManifest(jar)
	if !ok {
		return false, false
	}
	if strings.HasPrefix(attrs["main-class"], "org.springframework.boot.loader") {
		return true, true
	}
	_, hasVersion := attrs["spring-boot-version"]
	_, hasClassPath := attrs["class-path"]
	return hasVersion && !hasClassPath, true
}

// classpathFiles is the launched JAR followed by the files its manifest Class-Path lists, as ArchiveManifest.classpathOf does.
func classpathFiles(jar string) ([]string, bool) {
	attrs, ok := jarManifest(jar)
	if !ok {
		return nil, false
	}
	dir := filepath.Dir(jar)
	files := []string{jar}
	for _, token := range strings.Fields(attrs["class-path"]) {
		u, err := url.Parse(token)
		if err != nil {
			return nil, false
		}
		p := u.Path
		if !filepath.IsAbs(p) {
			p = filepath.Join(dir, p)
		}
		p = filepath.Clean(p)
		if st, err := os.Stat(p); err != nil || !st.Mode().IsRegular() {
			return nil, false
		}
		files = append(files, p)
	}
	return files, true
}

// hashOnce hashes a file once even when the classpath lists it several times.
type hashOnce struct {
	once sync.Once
	sum  string
	err  error
}

// describeClasspath hashes every file, in parallel, and returns the entries in classpath order.
func describeClasspath(jar string, files []string) ([]cpEntry, bool) {
	dir := filepath.Dir(jar)
	entries := make([]cpEntry, len(files))
	okAll := make([]bool, len(files))
	cache := map[string]*hashOnce{}
	for _, f := range files {
		if cache[f] == nil {
			cache[f] = &hashOnce{}
		}
	}
	var wg sync.WaitGroup
	for i, f := range files {
		wg.Add(1)
		go func() {
			defer wg.Done()
			st, err := os.Stat(f)
			if err != nil {
				return
			}
			c := cache[f]
			c.once.Do(func() { c.sum, c.err = sha256File(f) })
			rel, err := filepath.Rel(dir, f)
			if c.err != nil || err != nil {
				return
			}
			mod := st.ModTime().UTC().Format(time.RFC3339Nano)
			entries[i] = cpEntry{Name: filepath.ToSlash(rel), SizeBytes: st.Size(), SHA256: c.sum, LastModified: &mod}
			okAll[i] = true
		}()
	}
	wg.Wait()
	for _, ok := range okAll {
		if !ok {
			return nil, false
		}
	}
	return entries, true
}

func classpathMatches(trained, live []cpEntry) bool {
	if len(trained) != len(live) {
		return false
	}
	for i := range trained {
		if trained[i].Name != live[i].Name || trained[i].SizeBytes != live[i].SizeBytes || trained[i].SHA256 != live[i].SHA256 {
			return false
		}
	}
	return true
}

// sameSecond compares two modification times to the whole second, as the JVM does. A time that is missing on either side is
// not compared, as in the Java planner.
func sameSecond(a, b *string) bool {
	if a == nil || b == nil {
		return true
	}
	ta, errA := time.Parse(time.RFC3339Nano, *a)
	tb, errB := time.Parse(time.RFC3339Nano, *b)
	if errA != nil || errB != nil {
		return false
	}
	return ta.Unix() == tb.Unix()
}

func sha256File(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.CopyBuffer(h, f, make([]byte, 1<<20)); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

func equalStrings(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}
