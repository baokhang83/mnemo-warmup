// Interactive lifecycle diagram. Progressive enhancement: the page is fully readable without it.
(function () {
  var root = document.getElementById('arch');
  if (!root) return;

  var STEPS = {
    inject: {
      title: 'Inject — Helm post-renderer or <code>mnemo-warmup inject</code>',
      body: 'An annotated Deployment (<code>enabled</code>, <code>store</code>, <code>exit-mode</code>) is rendered, then rewritten offline. The injector adds a launcher, a trainer CronJob with its ServiceAccount/Role/RoleBinding, and either two init containers or a read-only <code>image</code> volume. It never contacts the cluster; <code>--preview</code> shows what changes without printing values.'
    },
    trainer: {
      title: 'Train — CronJob + Lease',
      body: 'A CronJob (every 10 minutes, no overlap, one-hour deadline) starts your application for real, stops it once the Spring context has refreshed (or an HTTP probe answers), and writes an AppCDS archive (JDK 21+) or a Leyden AOT cache (JDK 25). A Kubernetes <code>Lease</code> named after the fingerprint stops two trainers training the same archive. Training is asynchronous: your pods are never waiting for it.'
    },
    registry: {
      title: 'Store — an OCI registry',
      body: 'The archive and its manifest are pushed as an OCI artifact tagged <code>fp-&lt;hex&gt;</code>. The fingerprint covers the JDK build, classpath JAR contents and JVM options, so a changed option or image is a new archive, and rolling back finds the old one. Archives are never deleted by the tool: set a registry lifecycle policy.'
    },
    fetch: {
      title: 'Fetch — init container or image volume',
      body: 'Each pod looks up its fingerprint and verifies the archive digest before use. Default: two init containers (install, fetch). With <code>tools-delivery: image-volume</code> the tools are mounted read-only and the launcher fetches in the application container’s entrypoint, which removes the init containers. That needs Kubernetes 1.33+ with a supporting runtime.'
    },
    jvm: {
      title: 'Run — the JVM is PID 1',
      body: 'The launcher decides whether the archive fits this exact launch and, if so, adds <code>-XX:SharedArchiveFile</code> or <code>-XX:AOTCache</code>, then <code>exec</code>s <code>java</code> so the JVM is PID 1 and receives SIGTERM directly. If anything does not match, java starts the ordinary way and the log says why.'
    },
    fallback: {
      title: 'Fail open — an ordinary start',
      body: 'A missing, outdated, damaged or unreachable archive never stops a pod. <code>fetch</code> logs the reason and exits 0. With an unreachable registry, the end-to-end test saw pods become Ready about 14–15 s after deletion, the time <code>fetch</code> takes to give up. Deployment errors (a missing Secret, no image-volume support) are not covered by this fallback.'
    }
  };

  var MODES = {
    init:  { nodes: ['inject', 'trainer', 'registry', 'fetch', 'jvm'], edges: ['e-inject-trainer', 'e-trainer-registry', 'e-registry-fetch', 'e-fetch-jvm'], first: 'inject', text: 'Init-container delivery (default): two init containers run before every start.' },
    vol:   { nodes: ['inject', 'trainer', 'registry', 'fetch', 'jvm'], edges: ['e-inject-trainer', 'e-trainer-registry', 'e-registry-fetch', 'e-fetch-jvm'], first: 'fetch', text: 'Image-volume delivery: no init containers; the launcher fetches inside the application container.' },
    down:  { nodes: ['fetch', 'fallback', 'jvm'], edges: ['e-fetch-fallback', 'e-fallback-jvm'], first: 'fallback', text: 'Registry unreachable or archive rejected: the pod starts uncached.' }
  };

  var nodes = root.querySelectorAll('.node');
  var edges = root.querySelectorAll('.edge');
  var detail = root.querySelector('.detail');
  var tabs = root.querySelectorAll('.tabs button');
  var current = 'init';

  function showDetail(id) {
    var s = STEPS[id];
    detail.innerHTML = '<h3>' + s.title + '</h3><p>' + s.body + '</p><p class="mode-text"></p>';
    detail.querySelector('.mode-text').textContent = MODES[current].text;
    nodes.forEach(function (n) { n.classList.toggle('active', n.dataset.step === id); });
  }

  function setMode(mode) {
    current = mode;
    tabs.forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.mode === mode)); });
    var lit = MODES[mode];
    nodes.forEach(function (n) { n.classList.toggle('dim', lit.nodes.indexOf(n.dataset.step) < 0); });
    edges.forEach(function (e) { e.classList.toggle('dim', lit.edges.indexOf(e.id) < 0); });
    showDetail(lit.first);
  }

  nodes.forEach(function (n) {
    n.setAttribute('tabindex', '0');
    n.setAttribute('role', 'button');
    n.addEventListener('click', function () { showDetail(n.dataset.step); });
    n.addEventListener('keydown', function (ev) {
      if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); showDetail(n.dataset.step); }
    });
  });
  tabs.forEach(function (b) { b.addEventListener('click', function () { setMode(b.dataset.mode); }); });
  setMode('init');
})();
