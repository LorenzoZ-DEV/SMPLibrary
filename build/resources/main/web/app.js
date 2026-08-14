/* SMP Library dashboard client. Vanilla JS, no build step — served straight from the jar. */
(function () {
  'use strict';

  const $ = (sel, root) => (root || document).querySelector(sel);
  const $$ = (sel, root) => [...(root || document).querySelectorAll(sel)];

  /* ── navigation ────────────────────────────────────────────────────── */
  const PAGES = {
    overview:    ['Overview', 'Live server health'],
    performance: ['Performance', 'Real-time metrics from spark'],
    modules:     ['Modules', 'Turn features on and off in place'],
    chatgames:   ['Chat games', 'Rounds, answers and history'],
    config:      ['Configuration', "Edit the plugin's YAML files"],
    log:         ['Action log', 'Everything this dashboard changed']
  };

  const navItems = $$('.nav-item');
  let activeTab = 'overview';

  navItems.forEach(item => item.addEventListener('click', () => {
    selectTab(item.dataset.tab);
    closeSidebar();
  }));

  function selectTab(name) {
    if (!PAGES[name]) return;
    activeTab = name;
    navItems.forEach(i => i.classList.toggle('active', i.dataset.tab === name));
    $$('.tab-content').forEach(c => c.classList.toggle('active', c.id === 'tab-' + name));
    $('#pageTitle').textContent = PAGES[name][0];
    $('#pageSub').textContent = PAGES[name][1];
    if (name === 'log') $('#logDot').hidden = true;
    // The chart measures its container, which is unmeasurable while the tab is hidden.
    if (name === 'overview') requestAnimationFrame(renderChart);
    location.hash = name;
  }

  if (location.hash && PAGES[location.hash.slice(1)]) selectTab(location.hash.slice(1));
  else selectTab('overview');

  // Each tab writes its name to the hash, so back/forward should move between them.
  window.addEventListener('hashchange', () => {
    const name = location.hash.slice(1);
    if (PAGES[name] && name !== activeTab) selectTab(name);
  });

  /* ── sidebar drawer (narrow screens) ───────────────────────────────── */
  function openSidebar() { $('#side').classList.add('open'); $('#sideScrim').hidden = false; }
  function closeSidebar() { $('#side').classList.remove('open'); $('#sideScrim').hidden = true; }

  $('#sideToggle').addEventListener('click', openSidebar);
  $('#sideClose').addEventListener('click', closeSidebar);
  $('#sideScrim').addEventListener('click', closeSidebar);
  document.addEventListener('keydown', e => { if (e.key === 'Escape') closeSidebar(); });

  /* ── helpers ───────────────────────────────────────────────────────── */
  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /** A finite number, or null — the server sends null for every reading it does not have. */
  const num = v => (typeof v === 'number' && isFinite(v) ? v : null);
  const fixed = (v, digits) => (num(v) === null ? '--' : v.toFixed(digits == null ? 2 : digits));

  function fmtDuration(ms) {
    let s = Math.max(0, Math.floor(ms / 1000));
    const d = Math.floor(s / 86400); s %= 86400;
    const h = Math.floor(s / 3600); s %= 3600;
    const m = Math.floor(s / 60); s %= 60;
    if (d) return d + 'd ' + h + 'h ' + m + 'm';
    if (h) return h + 'h ' + m + 'm ' + s + 's';
    return m + 'm ' + s + 's';
  }

  /** Compact form for the topbar chip, where the full string does not fit. */
  function fmtShort(ms) {
    const s = Math.max(0, Math.floor(ms / 1000));
    if (s >= 86400) return Math.floor(s / 86400) + 'd ' + Math.floor((s % 86400) / 3600) + 'h';
    if (s >= 3600) return Math.floor(s / 3600) + 'h ' + Math.floor((s % 3600) / 60) + 'm';
    return Math.floor(s / 60) + 'm';
  }

  function fmtClock(ms) {
    const total = Math.max(0, Math.ceil(ms / 1000));
    return Math.floor(total / 60) + ':' + String(total % 60).padStart(2, '0');
  }

  function fmtBytes(b) {
    if (!Number.isFinite(b)) return '--';
    if (b < 1024) return b + ' B';
    if (b < 1048576) return (b / 1024).toFixed(1) + ' KB';
    if (b < 1073741824) return (b / 1048576).toFixed(0) + ' MB';
    return (b / 1073741824).toFixed(2) + ' GB';
  }

  /** Milliseconds as the shortest sensible unit — GC totals run from ms to hours. */
  function fmtMillis(ms) {
    if (num(ms) === null) return '--';
    if (ms < 1000) return Math.round(ms) + ' ms';
    if (ms < 60000) return (ms / 1000).toFixed(1) + ' s';
    if (ms < 3600000) return (ms / 60000).toFixed(1) + ' min';
    return (ms / 3600000).toFixed(1) + ' h';
  }

  function toast(message, kind) {
    const el = document.createElement('div');
    el.className = 'toast' + (kind ? ' ' + kind : '');
    el.textContent = message;
    $('#toasts').appendChild(el);
    setTimeout(() => el.remove(), kind === 'err' ? 7000 : 4000);
  }

  /** fetch + JSON with the shared error handling every call site needs. */
  async function api(url, options) {
    const res = await fetch(url, options);
    if (res.status === 401) {
      location.reload(); // session expired — the server will show the login page
      throw new Error('unauthorized');
    }
    const data = await res.json().catch(() => ({}));
    if (!res.ok || data.error) throw new Error(data.error || ('HTTP ' + res.status));
    return data;
  }

  function setMeter(el, percent, kind) {
    if (!el) return;
    el.style.width = Math.max(0, Math.min(100, num(percent) || 0)) + '%';
    el.className = kind || '';
  }

  /** The shared health grading: good / warn / bad from a value and two thresholds. */
  function grade(value, warnAt, badAt, higherIsBetter) {
    if (num(value) === null) return '';
    if (higherIsBetter) return value >= warnAt ? 'good' : value >= badAt ? 'warn' : 'bad';
    return value <= warnAt ? 'good' : value <= badAt ? 'warn' : 'bad';
  }

  /* ── connection indicator ──────────────────────────────────────────── */
  function setConnection(state) {
    $('#conn').dataset.state = state;
    $('#connText').textContent = state;
  }

  /* ── overview ──────────────────────────────────────────────────────── */
  const SPARK_POINTS = 48;
  const tpsHistory = [];
  const msptHistory = [];

  function renderOverview(d) {
    const spark = d.spark || {};
    const sparkTps = spark.available && spark.tps ? spark.tps : null;

    const tps = d.tps || {};
    const tps1 = num(tps['1m']) != null ? tps['1m'] : 0;
    $('#tps1').textContent = fixed(tps1);
    // spark's 5-second window is the one that actually moves; show it beside the averages.
    $('#tps5').textContent = sparkTps && num(sparkTps['5s']) !== null
      ? '5s ' + fixed(sparkTps['5s']) : '5m ' + fixed(tps['5m']);
    $('#tps15').textContent = '15m ' + fixed(tps['15m']);

    const tpsGrade = grade(tps1, 19.5, 18, true);
    const pill = $('#tpsPill');
    pill.className = 'pill ' + tpsGrade;
    pill.textContent = tpsGrade === 'good' ? 'healthy' : tpsGrade === 'warn' ? 'lagging' : 'overloaded';

    tpsHistory.push(sparkTps && num(sparkTps['5s']) !== null ? sparkTps['5s'] : tps1);
    while (tpsHistory.length > SPARK_POINTS) tpsHistory.shift();
    drawSpark($('#sparkTpsLine'), tpsHistory, 0, 20);

    const players = d.players || {};
    const playerPct = players.max ? (players.online / players.max) * 100 : 0;
    $('#players').textContent = (players.online || 0) + '/' + (players.max || 0);
    $('#playersPct').textContent = Math.round(playerPct) + '%';
    $('#playersMax').textContent = 'slots ' + (players.max || 0);
    setMeter($('#playersBar'), playerPct);

    renderRecord(players);

    const heap = d.heap || {};
    const heapPct = heap.max ? (heap.used / heap.max) * 100 : 0;
    $('#heapUsed').textContent = fmtBytes(heap.used);
    $('#heapPct').textContent = Math.round(heapPct) + '%';
    $('#heapMax').textContent = 'max ' + fmtBytes(heap.max);
    setMeter($('#heapBar'), heapPct, heapPct > 90 ? 'bad' : heapPct > 75 ? 'warn' : '');

    renderCpuCard(d);

    if (num(d.uptime) !== null) {
      $('#uptimeShort').textContent = fmtShort(d.uptime);
      $('#uptimeChip').title = 'Up for ' + fmtDuration(d.uptime);
    }

    const threads = d.threads || {};
    $('#threads').textContent = threads.count != null ? threads.count : '--';
    $('#threadDaemon').textContent = 'daemon ' + (threads.daemon != null ? threads.daemon : '--');

    const mods = d.modules || [];
    const running = mods.filter(m => m.running).length;
    const stalled = mods.filter(m => m.enabled && !m.running).length;
    $('#modulesRunning').textContent = running + '/' + mods.length;
    $('#modulesPill').textContent = stalled ? stalled + ' err' : 'ok';
    $('#modulesPill').className = 'pill ' + (stalled ? 'warn' : 'ghost');
    $('#modulesSub').textContent = stalled
      ? stalled + ' enabled but not running' : 'all enabled modules started';
    $('#moduleCount').textContent = mods.length ? running + '/' + mods.length : '';

    if (d.plugin) $('#pluginVersion').textContent = 'plugin v' + d.plugin;
    if (d.server) { $('#serverBrand').textContent = d.server; $('#serverBrand').title = d.server; }
  }

  /**
   * The all-time concurrent-player record, with the live count as a progress bar against it.
   * The bar is the point of the card: it answers "how close are we right now" at a glance.
   */
  function renderRecord(players) {
    const record = players.record;
    const card = $('#recordCard');
    const online = num(players.online) || 0;

    if (!record || !num(record.online)) {
      // playerstats is off, or nothing has been recorded and no figure was declared in config.
      $('#recordValue').textContent = '--';
      $('#recordPct').textContent = '--';
      $('#recordSub').textContent = 'no record yet';
      setMeter($('#recordBar'), 0);
      card.classList.remove('is-record');
      return;
    }

    const pct = (online / record.online) * 100;
    const tied = online >= record.online;

    $('#recordValue').textContent = online + '/' + record.online;
    $('#recordPct').textContent = Math.round(pct) + '%';
    $('#recordPct').className = 'pill ' + (tied ? 'good' : 'ghost');
    // A record with no timestamp came from the config key, so there is no date to show.
    $('#recordSub').textContent = tied ? 'at the all-time record right now'
      : record.at ? 'record ' + record.online + ' on ' + fmtDate(record.at)
      : 'record ' + record.online + ' players';
    setMeter($('#recordBar'), pct, tied ? 'record' : '');
    card.classList.toggle('is-record', tied);
  }

  const fmtDate = ts => new Date(ts).toLocaleDateString([], { day: 'numeric', month: 'short', year: 'numeric' });

  /** Prefers spark's reading, falls back to the JVM MXBean the server always sends. */
  function renderCpuCard(d) {
    const spark = d.spark || {};
    const sparkCpu = spark.available && spark.cpu ? spark.cpu : null;
    const jvm = d.cpu || {};

    const process = sparkCpu && sparkCpu.process ? num(sparkCpu.process['10s']) : num(jvm.process);
    const system = sparkCpu && sparkCpu.system ? num(sparkCpu.system['10s']) : num(jvm.system);

    $('#cpuSource').textContent = sparkCpu ? 'spark 10s' : 'jvm';
    $('#cpuProcess').textContent = process === null ? '--' : process.toFixed(1) + '%';
    $('#cpuSystem').textContent = 'system ' + (system === null ? '--' : system.toFixed(1) + '%');
    setMeter($('#cpuBar'), process, process > 90 ? 'bad' : process > 70 ? 'warn' : '');
  }

  function drawSpark(svg, values, min, max) {
    if (!svg || values.length < 2) return;
    const box = svg.viewBox.baseVal;
    const height = box.height || 32;
    const span = max - min || 1;
    const points = values.map((v, i) => {
      const x = (i / (values.length - 1)) * 100;
      const clamped = Math.max(min, Math.min(max, num(v) === null ? min : v));
      const y = (height - 2) - ((clamped - min) / span) * (height - 4);
      return x.toFixed(1) + ',' + y.toFixed(1);
    });
    $('.spark-line', svg).setAttribute('points', points.join(' '));
  }

  /* ── performance (spark) ───────────────────────────────────────────────
     Built once and then updated in place. Rebuilding this tab's markup on every
     one-second snapshot threw away the browser's text selection and made the
     numbers visibly flicker.                                                   */
  const TPS_WINDOWS = [['5s', '5 seconds'], ['10s', '10 seconds'], ['1m', '1 minute'],
                       ['5m', '5 minutes'], ['15m', '15 minutes']];
  const MSPT_WINDOWS = [['10s', '10 seconds'], ['1m', '1 minute'], ['5m', '5 minutes']];
  const CPU_WINDOWS = ['10s', '1m', '15m'];

  let sparkBuilt = false;
  const tpsTiles = {};
  const msptCells = {};
  const cpuRows = {};
  let gcSignature = '';

  function buildSparkSkeleton() {
    if (sparkBuilt) return;
    sparkBuilt = true;

    const tiles = $('#sparkTpsTiles');
    TPS_WINDOWS.forEach(([key, label]) => {
      const tile = document.createElement('div');
      tile.className = 'tile';
      tile.innerHTML = '<span class="tile-label">' + esc(label) + '</span>'
        + '<span class="tile-value">--</span>'
        + '<span class="tile-meter"><i style="width:0%"></i></span>';
      tiles.appendChild(tile);
      tpsTiles[key] = { value: $('.tile-value', tile), bar: $('.tile-meter i', tile) };
    });

    const msptBody = $('#msptBody');
    MSPT_WINDOWS.forEach(([key, label]) => {
      const row = document.createElement('tr');
      row.innerHTML = '<td>' + esc(label) + '</td>'
        + '<td class="mean">--</td><td>--</td><td>--</td><td>--</td><td>--</td>';
      msptBody.appendChild(row);
      msptCells[key] = [...row.children].slice(1);
    });

    const groups = $('#cpuGroups');
    [['process', 'This server process'], ['system', 'Whole machine']].forEach(([key, label]) => {
      const group = document.createElement('div');
      group.innerHTML = '<div class="cpu-group-title">' + esc(label) + '</div>';
      cpuRows[key] = {};
      CPU_WINDOWS.forEach(window => {
        const row = document.createElement('div');
        row.className = 'cpu-row';
        row.innerHTML = '<span class="w">' + window + '</span>'
          + '<span class="meter"><i style="width:0%"></i></span>'
          + '<span class="v">--</span>';
        group.appendChild(row);
        cpuRows[key][window] = { bar: $('.meter i', row), value: $('.v', row) };
      });
      groups.appendChild(group);
    });
  }

  function renderSpark(d) {
    const spark = (d && d.spark) || {};
    const offline = $('#sparkOffline');
    const panel = $('#sparkPanel');
    const tag = $('#sparkTag');

    if (!spark.available) {
      offline.hidden = false;
      panel.hidden = true;
      tag.hidden = true;
      $('#sparkReason').textContent = spark.reason || 'Waiting for the spark plugin…';
      return;
    }

    offline.hidden = true;
    panel.hidden = false;
    tag.hidden = false;
    // Naming the route makes an embedded-spark server (UniverseSpigot and the like) visibly
    // different from a plugin install, which is the first thing to check when this tab is empty.
    if (spark.source) {
      $('#sparkSource').textContent = 'rolling windows, via ' + spark.source;
      tag.title = 'Hooked into spark via ' + spark.source;
    }
    buildSparkSkeleton();

    // ── TPS ──
    const tps = spark.tps || {};
    TPS_WINDOWS.forEach(([key]) => {
      const cell = tpsTiles[key];
      const value = num(tps[key]);
      cell.value.textContent = value === null ? '--' : value.toFixed(2);
      cell.value.className = 'tile-value ' + grade(value, 19.5, 18, true);
      cell.bar.style.width = (value === null ? 0 : Math.min(100, (value / 20) * 100)) + '%';
    });

    // ── MSPT ──
    const mspt = spark.mspt || {};
    MSPT_WINDOWS.forEach(([key]) => {
      const info = mspt[key] || {};
      const cells = msptCells[key];
      const values = [info.mean, info.median, info.p95, info.min, info.max];
      values.forEach((value, i) => { cells[i].textContent = num(value) === null ? '--' : fixed(value) + ' ms'; });
      // A tick has 50 ms before the server falls behind; colour the mean against that budget.
      cells[0].className = 'mean ' + gradeToColour(grade(info.mean, 25, 45, false));
    });

    const live = mspt['10s'] && num(mspt['10s'].mean) !== null ? mspt['10s'].mean : null;
    if (live !== null) {
      msptHistory.push(live);
      while (msptHistory.length > SPARK_POINTS) msptHistory.shift();
      drawSpark($('#sparkMsptLine'), msptHistory, 0, Math.max(50, ...msptHistory));
    }

    // ── CPU ──
    const cpu = spark.cpu || {};
    ['process', 'system'].forEach(key => {
      const series = cpu[key] || {};
      CPU_WINDOWS.forEach(window => {
        const row = cpuRows[key][window];
        const value = num(series[window]);
        row.value.textContent = value === null ? '--' : value.toFixed(1) + '%';
        setMeter(row.bar, value, value > 90 ? 'bad' : value > 70 ? 'warn' : '');
      });
    });

    renderGc(spark.gc || []);
  }

  /** The tile/cell colour classes reuse the pill vocabulary. */
  function gradeToColour(g) { return g === 'good' ? '' : g; }

  function renderGc(collectors) {
    const signature = collectors.map(c => c.name + c.collections + c.totalTime).join('|');
    if (signature === gcSignature) return;
    gcSignature = signature;

    const body = $('#gcBody');
    if (!collectors.length) {
      body.innerHTML = '<tr class="empty"><td colspan="5">No collections yet.</td></tr>';
      return;
    }
    body.innerHTML = collectors.map(c =>
      '<tr><td>' + esc(c.name) + '</td>'
      // Pinned locale: the browser's own would render 18422 as "18.422" next to real
      // decimals like "11.66 ms", which reads as eighteen point four.
      + '<td>' + (c.collections != null ? c.collections.toLocaleString('en-US') : '--') + '</td>'
      + '<td>' + fmtMillis(c.totalTime) + '</td>'
      + '<td>' + fixed(c.avgTime) + ' ms</td>'
      + '<td>' + (num(c.avgFrequency) ? 'every ' + fmtMillis(c.avgFrequency) : '--') + '</td></tr>'
    ).join('');
  }

  /* ── players chart ─────────────────────────────────────────────────────
     Single series over time -> area chart, one hue, no legend (the title names
     it). The peak is the only direct-labelled point; everything else is reachable
     through the crosshair tooltip and the table view twin.                      */
  const SVG_NS = 'http://www.w3.org/2000/svg';
  const PAD = { top: 18, right: 14, bottom: 24, left: 38 };

  let chartData = { points: [], peak: null, sampleInterval: 60000, enabled: true };

  function el(name, attrs, text) {
    const node = document.createElementNS(SVG_NS, name);
    for (const key in attrs) node.setAttribute(key, attrs[key]);
    if (text != null) node.textContent = text;
    return node;
  }

  const hhmm = ts => new Date(ts).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });

  /** Rounds an axis maximum up to a clean number so ticks read 0 / 5 / 10. */
  function niceMax(value) {
    if (value <= 4) return 4;
    const step = value <= 10 ? 2 : value <= 50 ? 5 : value <= 200 ? 25 : 100;
    return Math.ceil(value / step) * step;
  }

  function fetchPlayerHistory() {
    api('/api/players/history')
      .then(data => {
        chartData = data;
        renderChartStats();
        renderChart();
      })
      .catch(() => { /* the overview still works without the chart */ });
  }

  /** The headline numbers live outside the plot, so they read without hovering. */
  function renderChartStats() {
    const peak = chartData.peak;
    $('#peakValue').textContent = peak && peak.online > 0 ? peak.online : '--';
    $('#peakAt').textContent = peak && peak.online > 0 ? 'at ' + hhmm(peak.at) : 'no players yet today';
    $('#avgValue').textContent = num(chartData.average) !== null ? chartData.average.toFixed(1) : '--';
  }

  function renderChart() {
    const svg = $('#playerChart');
    const wrap = $('#chartWrap');
    const empty = $('#chartEmpty');

    const width = wrap.clientWidth;
    if (!width) return;                      // hidden tab: nothing to measure yet

    const points = chartData.points || [];
    svg.replaceChildren();

    if (!chartData.enabled) {
      empty.hidden = false;
      empty.textContent = 'The playerstats module is not running.';
      svg.setAttribute('height', 120);
      return;
    }
    if (points.length < 2) {
      empty.hidden = false;
      empty.textContent = 'Waiting for the first samples…';
      svg.setAttribute('height', 120);
      return;
    }
    empty.hidden = true;

    // Height includes the x-axis band, so the card never grows a nested scrollbar.
    const height = 200;
    svg.setAttribute('height', height);
    svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);

    const plotW = width - PAD.left - PAD.right;
    const plotH = height - PAD.top - PAD.bottom;

    const t0 = points[0][0];
    const t1 = Math.max(points[points.length - 1][0], chartData.now || 0);
    const span = Math.max(1, t1 - t0);
    const yMax = niceMax(Math.max(...points.map(p => p[1]), chartData.peak ? chartData.peak.online : 0));

    const x = ts => PAD.left + ((ts - t0) / span) * plotW;
    const y = v => PAD.top + plotH - (v / yMax) * plotH;

    // ── grid + y ticks (solid hairlines, one step off the surface) ──
    const tickCount = 4;
    for (let i = 0; i <= tickCount; i++) {
      const value = (yMax / tickCount) * i;
      const gy = Math.round(y(value)) + 0.5;
      svg.appendChild(el('line', { class: 'chart-grid', x1: PAD.left, x2: width - PAD.right, y1: gy, y2: gy }));
      svg.appendChild(el('text', {
        class: 'chart-tick', x: PAD.left - 8, y: gy + 3.5, 'text-anchor': 'end'
      }, String(Math.round(value))));
    }

    // ── x ticks, ~4 across the window ──
    for (let i = 0; i <= 4; i++) {
      const ts = t0 + (span / 4) * i;
      const tx = x(ts);
      svg.appendChild(el('text', {
        class: 'chart-tick', x: tx, y: height - 7,
        'text-anchor': i === 0 ? 'start' : i === 4 ? 'end' : 'middle'
      }, hhmm(ts)));
    }

    // ── the series, split on gaps ──
    // A gap wider than a few sample intervals means the server was down: draw a break
    // instead of a straight line that would claim players we never counted.
    // Compare against the resolution of the points we were sent (the server's bucket
    // width once it downsamples), never the raw sample interval. 3x, because a bucket
    // reports the timestamp of its maximum: two neighbours can legitimately sit ~2
    // buckets apart, and a real outage is many buckets wide anyway.
    const gapLimit = (chartData.resolution || chartData.sampleInterval || 60000) * 3;
    const segments = [];
    let run = [points[0]];
    for (let i = 1; i < points.length; i++) {
      if (points[i][0] - points[i - 1][0] > gapLimit) {
        segments.push(run);
        run = [];
      }
      run.push(points[i]);
    }
    segments.push(run);

    segments.forEach(segment => {
      if (segment.length < 2) return;
      const line = segment.map((p, i) => (i ? 'L' : 'M') + x(p[0]).toFixed(1) + ' ' + y(p[1]).toFixed(1)).join(' ');
      const base = PAD.top + plotH;
      svg.appendChild(el('path', {
        class: 'chart-area',
        d: line + ' L' + x(segment[segment.length - 1][0]).toFixed(1) + ' ' + base
           + ' L' + x(segment[0][0]).toFixed(1) + ' ' + base + ' Z'
      }));
      svg.appendChild(el('path', { class: 'chart-line', d: line }));
    });

    // ── the peak: the one point worth labelling directly ──
    const peak = chartData.peak;
    if (peak && peak.online > 0 && peak.at >= t0) {
      const px = x(peak.at);
      const py = y(peak.online);
      svg.appendChild(el('circle', { class: 'chart-peak-dot', cx: px, cy: py, r: 4.5 }));

      const label = peak.online + ' at ' + hhmm(peak.at);
      // Clamp so the label never overflows the plot; flip below the dot near the top edge.
      // 6.7px/char is the monospace advance at 11px, rounded up — under-measuring here
      // is what clips the first or last character.
      const half = Math.min((label.length * 6.7) / 2, (width - PAD.left - PAD.right) / 2);
      const lx = Math.min(Math.max(px, PAD.left + half), width - PAD.right - half);
      const above = py - 12 > PAD.top;
      svg.appendChild(el('text', {
        class: 'chart-peak-label', x: lx, y: above ? py - 11 : py + 18, 'text-anchor': 'middle'
      }, label));
    }

    wireCrosshair(svg, points, x, y, width, height);
    renderChartTable(points);
  }

  /** Crosshair + tooltip. The hit area is the whole plot, so there is no pinpoint target. */
  function wireCrosshair(svg, points, x, y, width, height) {
    const cursor = el('line', { class: 'chart-cursor', y1: PAD.top, y2: PAD.top + (height - PAD.top - PAD.bottom) });
    const dot = el('circle', { class: 'chart-cursor-dot', r: 4.5 });
    cursor.style.display = dot.style.display = 'none';
    svg.appendChild(cursor);
    svg.appendChild(dot);

    const tip = $('#chartTip');
    const hit = el('rect', {
      x: PAD.left, y: PAD.top, width: Math.max(0, width - PAD.left - PAD.right),
      height: Math.max(0, height - PAD.top - PAD.bottom), fill: 'transparent'
    });
    svg.appendChild(hit);

    const move = event => {
      const box = svg.getBoundingClientRect();
      const px = (event.clientX - box.left) * (width / box.width);

      let nearest = points[0];
      let best = Infinity;
      for (const point of points) {
        const distance = Math.abs(x(point[0]) - px);
        if (distance < best) { best = distance; nearest = point; }
      }

      const nx = x(nearest[0]);
      const ny = y(nearest[1]);
      cursor.setAttribute('x1', nx);
      cursor.setAttribute('x2', nx);
      dot.setAttribute('cx', nx);
      dot.setAttribute('cy', ny);
      cursor.style.display = dot.style.display = '';

      tip.hidden = false;
      tip.innerHTML = '<b>' + nearest[1] + '</b> online &middot; ' + esc(hhmm(nearest[0]));
      tip.style.top = Math.max(0, ny - 10) + 'px';
      // Keep the (centre-anchored) tooltip inside the card at both ends of the plot.
      const halfTip = tip.offsetWidth / 2;
      tip.style.left = Math.min(Math.max(nx, halfTip), width - halfTip) + 'px';
    };

    hit.addEventListener('mousemove', move);
    hit.addEventListener('mouseleave', () => {
      cursor.style.display = dot.style.display = 'none';
      tip.hidden = true;
    });
  }

  /** The table-view twin: hourly maxima, so every value is reachable without hovering. */
  function renderChartTable(points) {
    const buckets = new Map();
    for (const [ts, online] of points) {
      const hour = new Date(ts);
      hour.setMinutes(0, 0, 0);
      const key = hour.getTime();
      buckets.set(key, Math.max(buckets.get(key) || 0, online));
    }
    $('#chartTableBody').innerHTML = [...buckets.entries()]
      .sort((a, b) => b[0] - a[0])
      .map(([ts, online]) => '<tr><td>' + esc(hhmm(ts)) + '</td><td>' + online + '</td></tr>')
      .join('');
  }

  $('#btnChartTable').addEventListener('click', () => {
    const table = $('#chartTable');
    const showing = table.hidden;
    table.hidden = !showing;
    $('#chartWrap').hidden = showing;
    $('#btnChartTable').setAttribute('aria-pressed', String(showing));
    $('span', $('#btnChartTable')).textContent = showing ? 'Chart' : 'Table';
    if (!showing) renderChart();
  });

  let resizeTimer;
  window.addEventListener('resize', () => {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(renderChart, 150);
  });

  /* ── modules ───────────────────────────────────────────────────────── */
  const pending = new Set();
  let moduleSignature = '';
  let moduleFilter = '';

  $('#moduleSearch').addEventListener('input', e => {
    moduleFilter = e.target.value.trim().toLowerCase();
    moduleSignature = '';           // force a redraw with the new filter
    renderModules(lastSnapshot);
  });

  function renderModules(d) {
    if (!d) return;
    const all = d.modules || [];
    const mods = moduleFilter ? all.filter(m => m.id.toLowerCase().includes(moduleFilter)) : all;

    const signature = mods.map(m => m.id + m.enabled + m.running + pending.has(m.id)).join('|');
    if (signature === moduleSignature) return;
    moduleSignature = signature;

    const grid = $('#moduleGrid');
    if (!mods.length) {
      grid.innerHTML = '<p class="muted">No module matches that filter.</p>';
      return;
    }

    grid.innerHTML = mods.map(m => {
      const id = esc(m.id);
      const busy = pending.has(m.id);
      // enabled-but-not-running means the module threw while starting.
      const state = !m.enabled ? 'off' : m.running ? 'on' : 'warn';
      const label = state === 'on' ? 'ON' : state === 'warn' ? 'ERR' : 'OFF';
      const desc = state === 'on' ? 'Running'
        : state === 'warn' ? 'Enabled but not running — check console'
        : 'Disabled';
      // The web module is the one serving this page: toggling it off would cut the wire.
      const control = m.id === 'web'
        ? '<span class="status status-off" title="This dashboard">SELF</span>'
        : `<button class="switch" data-module="${id}" role="switch"
             aria-checked="${state !== 'off'}" aria-label="Toggle ${id}"${busy ? ' disabled' : ''}></button>`;

      return `<article class="module-card is-${state}${busy ? ' is-busy' : ''}">
        <div class="module-body">
          <div class="name">${id}</div>
          <div class="desc${state === 'warn' ? ' warn' : ''}">${desc}</div>
        </div>
        <div class="module-actions">
          <span class="status status-${state}">${label}</span>
          ${control}
        </div>
      </article>`;
    }).join('');
  }

  $('#moduleGrid').addEventListener('click', event => {
    const btn = event.target.closest('.switch');
    if (!btn || btn.disabled) return;

    const id = btn.dataset.module;
    if (pending.has(id)) return;
    pending.add(id);
    moduleSignature = '';
    renderModules(lastSnapshot);

    api('/api/module/' + encodeURIComponent(id) + '/toggle', { method: 'POST' })
      .then(data => {
        // Apply the state the server reports straight away. Waiting for the next snapshot
        // left the card showing the old state, and showed nothing at all if the stream
        // happened to be down.
        if (lastSnapshot && Array.isArray(data.modules)) lastSnapshot.modules = data.modules;
        if (data.log) renderLog(data.log);
        if (data.warning) toast(data.warning, 'warn');
        else toast('Module "' + id + '" is now ' + (data.enabled ? 'ON' : 'OFF'), 'ok');
      })
      .catch(e => toast('Toggle failed: ' + e.message, 'err'))
      .finally(() => {
        pending.delete(id);
        moduleSignature = '';
        renderModules(lastSnapshot);
        if (lastSnapshot) renderOverview(lastSnapshot);
      });
  });

  /* ── chat games ────────────────────────────────────────────────────── */
  let round = { active: false, endsAt: 0, answer: null };
  let roundTotalMs = 0;   // captured once per round so the bar drains instead of sitting full
  let revealed = false;
  let starting = false;
  let historySignature = '';

  function renderChatGames(d) {
    const cg = (d && d.chatgames) || {};
    const chip = $('#roundStatus');

    if (!cg.enabled) {
      chip.className = 'status-chip off';
      chip.textContent = 'module off';
      $('#roundPrompt').textContent = 'The chatgames module is not running.';
      $('#roundType').textContent = '';
      $('#roundClock').textContent = '';
      $('#btnStartGame').disabled = true;
      $('#cgDot').hidden = true;
      round = { active: false, endsAt: 0, answer: null };
      return;
    }

    $('#btnStartGame').disabled = starting;

    if (cg.endsAt !== round.endsAt) {
      revealed = false;
      roundTotalMs = Math.max(1, (cg.endsAt || 0) - Date.now());
    }
    round = { active: !!cg.active, endsAt: cg.endsAt || 0, answer: cg.answer };

    chip.className = 'status-chip' + (cg.active ? ' live' : '');
    chip.textContent = cg.active ? 'round live' : 'waiting';
    $('#cgDot').hidden = !cg.active || activeTab === 'chatgames';
    $('#roundType').textContent = cg.type ? cg.type.toLowerCase() : '';
    $('#roundPrompt').textContent = cg.active && cg.prompt ? cg.prompt : 'No round in progress.';

    const reveal = $('#btnReveal');
    const answer = $('#roundAnswer');
    reveal.hidden = !cg.active;
    answer.hidden = !cg.active || !revealed;
    if (revealed) answer.textContent = cg.answer || '';
    $('span', reveal).textContent = revealed ? 'Hide answer' : 'Reveal answer';

    renderHistory(Array.isArray(cg.history) ? cg.history : []);
    tickRound();
  }

  function renderHistory(history) {
    const signature = history.map(e => e.time + e.event + e.detail).join('|');
    if (signature === historySignature) return;
    historySignature = signature;

    const body = $('#cgBody');
    if (!history.length) {
      body.innerHTML = '<tr class="empty"><td colspan="4">No rounds yet.</td></tr>';
      return;
    }
    body.innerHTML = history.map(e => {
      const cls = e.event === 'won' ? 'status-on' : e.event === 'timeout' ? 'status-warn' : 'status-off';
      return `<tr>
        <td>${esc(e.time)}</td>
        <td><span class="status ${cls}">${esc(e.event)}</span></td>
        <td>${esc((e.type || '').toLowerCase())}</td>
        <td class="detail">${esc(e.detail)}</td>
      </tr>`;
    }).join('');
  }

  /** Runs every second so the countdown moves between server snapshots. */
  function tickRound() {
    const clock = $('#roundClock');
    const bar = $('#roundBar');
    if (!round.active || !round.endsAt) {
      clock.textContent = '';
      bar.style.width = '0%';
      return;
    }
    const left = round.endsAt - Date.now();
    clock.textContent = fmtClock(left);
    setMeter(bar, (left / (roundTotalMs || 1)) * 100,
      left < 15000 ? 'bad' : left < 45000 ? 'warn' : '');
  }
  setInterval(tickRound, 1000);

  $('#btnReveal').addEventListener('click', () => {
    revealed = !revealed;
    $('#roundAnswer').hidden = !revealed;
    $('#roundAnswer').textContent = round.answer || '';
    $('span', $('#btnReveal')).textContent = revealed ? 'Hide answer' : 'Reveal answer';
  });

  $('#btnStartGame').addEventListener('click', () => {
    if (starting) return;
    starting = true;
    $('#btnStartGame').disabled = true;

    api('/api/chatgames/start', { method: 'POST' })
      .then(data => {
        toast('Started a ' + (data.type || '').toLowerCase() + ' round', 'ok');
        if (data.log) renderLog(data.log);
      })
      .catch(e => toast('Could not start a round: ' + e.message, 'err'))
      .finally(() => {
        starting = false;
        $('#btnStartGame').disabled = false;
      });
  });

  /* ── config editor ─────────────────────────────────────────────────── */
  const editor = $('#configEditor');
  const gutter = $('#gutter');
  let scope = '';
  let readOnly = true;
  let savedContent = '';

  function loadScopes() {
    api('/api/scopes')
      .then(scopes => {
        $('#scopeTabs').innerHTML = scopes.map(s =>
          `<button class="scope-tab" data-scope="${esc(s.scope)}" data-ro="${s.readonly}">${esc(s.scope)}.yml</button>`
        ).join('');
        if (scopes.length) selectScope(scopes[0].scope, scopes[0].readonly);
      })
      .catch(() => toast('Could not list config files', 'err'));
  }

  $('#scopeTabs').addEventListener('click', event => {
    const tab = event.target.closest('.scope-tab');
    if (!tab || tab.dataset.scope === scope) return;
    if (isDirty() && !confirm('Discard unsaved changes to ' + scope + '?')) return;
    selectScope(tab.dataset.scope, tab.dataset.ro === 'true');
  });

  function selectScope(name, ro) {
    scope = name;
    readOnly = ro;
    $$('.scope-tab').forEach(t => t.classList.toggle('active', t.dataset.scope === name));
    $('#scopeReadonly').hidden = !ro;
    editor.readOnly = ro;
    $('#configResult').hidden = true;
    loadScope();
  }

  function loadScope() {
    editor.value = 'Loading…';
    api('/api/config/' + encodeURIComponent(scope))
      .then(data => {
        editor.value = data.content || '';
        savedContent = editor.value;
        syncEditorChrome();
      })
      .catch(e => {
        editor.value = '';
        toast('Load failed: ' + e.message, 'err');
      });
  }

  const isDirty = () => !readOnly && editor.value !== savedContent;

  let gutterLines = 0;

  function syncEditorChrome() {
    const lines = editor.value.split('\n').length;
    if (lines !== gutterLines) {
      gutterLines = lines;
      gutter.textContent = Array.from({ length: lines }, (_, i) => i + 1).join('\n');
    }
    const dirty = isDirty();
    $('#dirtyFlag').hidden = !dirty;
    $('#cfgDot').hidden = !dirty;
    $('#btnApplyConfig').disabled = !dirty;
    $('#btnRevert').disabled = !dirty;
  }

  editor.addEventListener('input', syncEditorChrome);
  editor.addEventListener('scroll', () => { gutter.scrollTop = editor.scrollTop; });

  // Tab should indent rather than jump to the next control — this is a YAML editor.
  editor.addEventListener('keydown', event => {
    if (event.key !== 'Tab' || event.ctrlKey || event.altKey) return;
    event.preventDefault();
    const start = editor.selectionStart;
    editor.setRangeText('  ', start, editor.selectionEnd, 'end');
    syncEditorChrome();
  });

  $('#btnRevert').addEventListener('click', () => {
    editor.value = savedContent;
    syncEditorChrome();
    toast('Reverted to the last saved version');
  });

  $('#btnApplyConfig').addEventListener('click', saveConfig);

  document.addEventListener('keydown', event => {
    if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 's') return;
    event.preventDefault();
    if (activeTab === 'config') saveConfig();
  });

  window.addEventListener('beforeunload', event => {
    if (!isDirty()) return;
    event.preventDefault();
    event.returnValue = '';
  });

  function saveConfig() {
    if (!scope || readOnly || !isDirty()) return;
    const result = $('#configResult');
    const btn = $('#btnApplyConfig');

    btn.disabled = true;
    result.hidden = false;
    result.className = 'result';
    result.textContent = 'Saving…';

    const submitted = editor.value;
    api('/api/config/' + encodeURIComponent(scope), { method: 'POST', body: submitted })
      .then(data => {
        savedContent = submitted;
        result.className = 'result ok';
        result.textContent = scope + '.yml saved — the plugin reloaded it.';
        if (data.log) renderLog(data.log);
        toast('Saved ' + scope, 'ok');
      })
      .catch(e => {
        result.className = 'result err';
        // The server writes a backup, validates the YAML and rolls back on failure.
        result.textContent = e.message;
        toast('Save rejected', 'err');
      })
      .finally(syncEditorChrome);
  }

  /* ── log ───────────────────────────────────────────────────────────── */
  let logLines = [];
  let logFilter = '';

  $('#logSearch').addEventListener('input', e => {
    logFilter = e.target.value.trim().toLowerCase();
    renderLog();
  });

  function renderLog(lines) {
    if (Array.isArray(lines)) {
      if (lines.length > logLines.length && activeTab !== 'log') $('#logDot').hidden = false;
      logLines = lines;
    }

    const shown = logFilter
      ? logLines.filter(l => String(l).toLowerCase().includes(logFilter))
      : logLines;

    const container = $('#logContainer');
    if (!shown.length) {
      container.innerHTML = '<div class="log-empty">' +
        (logFilter ? 'Nothing matches that filter.' : 'Waiting for events…') + '</div>';
      return;
    }

    container.innerHTML = shown.map(line => {
      const match = /^\[LOG\]\s+(\S+)\s+([\s\S]*)$/.exec(String(line));
      return match
        ? `<div class="log-entry"><span class="time">${esc(match[1])}</span><span class="msg">${esc(match[2])}</span></div>`
        : `<div class="log-entry"><span class="msg">${esc(line)}</span></div>`;
    }).join('');
  }

  /* ── live stream ───────────────────────────────────────────────────── */
  let lastSnapshot = null;
  let stream = null;
  let retryDelay = 1000;
  let pollTimer = null;

  function applySnapshot(data) {
    lastSnapshot = data;
    renderOverview(data);
    renderModules(data);
    renderChatGames(data);
    renderSpark(data);
  }

  /**
   * While the event stream is down, fall back to polling. Without this the whole UI froze
   * on the last frame it received and gave no sign that its numbers had stopped being true.
   */
  function startPolling() {
    if (pollTimer) return;
    pollTimer = setInterval(() => {
      api('/api/stats').then(applySnapshot).catch(() => {});
    }, 5000);
  }

  function stopPolling() {
    clearInterval(pollTimer);
    pollTimer = null;
  }

  function connect() {
    setConnection('connecting');
    stream = new EventSource('/api/stream');

    stream.onopen = () => { setConnection('live'); retryDelay = 1000; stopPolling(); };

    stream.onmessage = event => {
      try { applySnapshot(JSON.parse(event.data)); } catch (ignored) { /* partial frame */ }
    };

    stream.addEventListener('log', event => {
      try {
        const lines = JSON.parse(event.data);
        if (Array.isArray(lines)) renderLog(lines);
      } catch (ignored) { /* partial frame */ }
    });

    stream.onerror = () => {
      setConnection('offline');
      stream.close();
      startPolling();
      // Back off up to 15s so a stopped server doesn't get hammered.
      setTimeout(connect, retryDelay);
      retryDelay = Math.min(retryDelay * 2, 15000);
    };
  }

  $('#btnLogout').addEventListener('click', () => {
    if (stream) stream.close();
    stopPolling();
    fetch('/api/logout', { method: 'POST' }).finally(() => location.reload());
  });

  /* ── boot ──────────────────────────────────────────────────────────── */
  loadScopes();
  api('/api/stats').then(applySnapshot).catch(() => {});
  api('/api/log').then(renderLog).catch(() => {});

  // The history is a separate, heavier payload than the 1s snapshot — poll it slowly.
  fetchPlayerHistory();
  setInterval(fetchPlayerHistory, 60000);

  connect();
})();
