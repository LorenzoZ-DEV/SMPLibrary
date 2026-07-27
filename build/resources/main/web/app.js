(function () {
  'use strict';

  const $ = (s, p) => (p || document).querySelector(s);
  const $$ = (s, p) => [...(p || document).querySelectorAll(s)];

  /* ---------- tab routing ---------- */
  const tabs = $$('.tab');
  const contents = {};
  $$('.tab-content').forEach(el => { contents[el.id] = el; });

  tabs.forEach(t => {
    t.addEventListener('click', () => {
      tabs.forEach(x => x.classList.remove('active'));
      t.classList.add('active');
      Object.values(contents).forEach(c => c.classList.remove('active'));
      const target = contents['tab-' + t.dataset.tab];
      if (target) target.classList.add('active');
    });
  });

  /* ---------- helpers ---------- */
  function fmtUptime(ms) {
    let s = Math.floor(ms / 1000);
    const d = Math.floor(s / 86400); s %= 86400;
    const h = Math.floor(s / 3600); s %= 3600;
    const m = Math.floor(s / 60); s %= 60;
    return d + 'd ' + h + 'h ' + m + 'm ' + s + 's';
  }

  function fmtBytes(b) {
    if (b < 1024) return b + ' B';
    if (b < 1024 * 1024) return (b / 1024).toFixed(1) + ' KB';
    return (b / 1024 / 1024).toFixed(1) + ' MB';
  }

  function escapeHtml(s) {
    const d = document.createElement('div');
    d.textContent = s;
    return d.innerHTML;
  }

  /* ---------- overview ---------- */
  function renderOverview(d) {
    $('#tps1').textContent = d.tps['1m'].toFixed(2);
    $('#tps5').textContent = '5m: ' + d.tps['5m'].toFixed(2);
    $('#tps15').textContent = '15m: ' + d.tps['15m'].toFixed(2);
    $('#players').textContent = d.players.online + '/' + d.players.max;
    $('#playersMax').textContent = 'Max: ' + d.players.max;
    $('#uptime').textContent = fmtUptime(d.uptime);
    $('#heapUsed').textContent = fmtBytes(d.heap.used) + ' / ' + fmtBytes(d.heap.total);
    $('#heapMax').textContent = 'Max: ' + fmtBytes(d.heap.max);
    $('#threads').textContent = d.threads.count;
    $('#threadDaemon').textContent = 'Daemon: ' + d.threads.daemon;

    const tps1 = d.tps['1m'];
    const tpsCard = $('.card-tps .card-value');
    tpsCard.style.color = tps1 < 18 ? 'var(--red)' : tps1 < 19.5 ? 'var(--yellow)' : 'var(--green)';
  }

  /* ---------- modules ---------- */
  function renderModules(d) {
    const grid = $('#moduleGrid');
    if (!grid) return;
    const mods = d.modules || [];
    grid.innerHTML = mods.map(m => {
      const id = escapeHtml(m.id);
      const en = m.enabled;
      const canToggle = id !== 'web';
      return `
      <div class="module-card" data-module="${id}">
        <div class="info">
          <div class="name">${id}</div>
          <div class="desc">${en ? 'Enabled' : 'Disabled'}</div>
        </div>
        <div class="actions">
          <span class="status ${en ? 'status-on' : 'status-off'}">${en ? 'ON' : 'OFF'}</span>
          ${canToggle ? `<button class="btn-toggle ${en ? 'on' : 'off'}" data-module="${id}">Toggle</button>` : ''}
        </div>
      </div>`;
    }).join('');

    grid.querySelectorAll('.btn-toggle').forEach(btn => {
      btn.addEventListener('click', function () {
        const mid = this.dataset.module;
        this.disabled = true;
        this.textContent = '...';
        fetch('/api/module/' + mid + '/toggle', { method: 'POST' })
          .then(r => r.json())
          .then(data => {
            if (data.error) { alert('Error: ' + data.error); return; }
            if (data.stats) {
              renderOverview(data.stats);
              renderModules(data.stats);
              renderChatGames(data.stats);
            }
            if (data.log) renderLog(data.log);
          })
          .catch(e => { alert('Request failed: ' + e.message); })
          .finally(() => { this.disabled = false; });
      });
    });
  }

  /* ---------- chat games ---------- */
  function renderChatGames(d) {
    const cg = d.chatgames || {};
    const status = $('#cgStatus');
    if (!cg.enabled) {
      status.innerHTML = '<span class="lbl">Status:</span> <span class="val red">Disabled</span>';
      return;
    }
    let html = '<span class="lbl">Status:</span> ';
    html += cg.active
      ? '<span class="val green">Active</span>'
      : '<span class="val red">Waiting</span>';
    if (cg.type) html += ' &middot; <span class="lbl">Type:</span> <span class="val">' + escapeHtml(cg.type) + '</span>';
    if (cg.active) html += ' &middot; <span class="lbl">Answer hidden</span>';
    status.innerHTML = html;

    const tbody = $('#cgBody');
    if (!tbody) return;
    const history = cg.history || [];
    tbody.innerHTML = history.map(e => `
      <tr>
        <td>${escapeHtml(e.time)}</td>
        <td><span class="status ${e.event === 'won' ? 'status-on' : e.event === 'timeout' ? 'status-off' : 'badge-ro'}">${escapeHtml(e.event)}</span></td>
        <td>${escapeHtml(e.type)}</td>
        <td>${escapeHtml(e.detail)}</td>
      </tr>
    `).join('');
  }

  /* ---------- config editor ---------- */
  let currentScope = '';
  let scopeReadonly = true;

  function initConfigEditor() {
    fetch('/api/scopes')
      .then(r => r.json())
      .then(scopes => {
        const sel = $('#scopeSelect');
        sel.innerHTML = scopes.map(s =>
          '<option value="' + s.scope + '"' + (s.readonly ? ' data-ro="1"' : '') + '>' + s.scope + '</option>'
        ).join('');
        sel.addEventListener('change', () => updateScopeLabel());
        updateScopeLabel();
      })
      .catch(() => {});
  }

  function updateScopeLabel() {
    const sel = $('#scopeSelect');
    const opt = sel.options[sel.selectedIndex];
    const ro = opt && opt.dataset.ro === '1';
    scopeReadonly = ro;
    $('#scopeReadonly').style.display = ro ? 'inline' : 'none';
    $('#btnApplyConfig').style.display = ro ? 'none' : 'inline-block';
    currentScope = sel.value;
  }

  function loadConfig() {
    if (!currentScope) return;
    const ta = $('#configEditor');
    const res = $('#configResult');
    res.className = 'config-result';
    ta.value = 'Loading...';
    fetch('/api/config/' + currentScope)
      .then(r => r.json())
      .then(data => {
        ta.value = data.content || '';
      })
      .catch(e => {
        ta.value = 'Error: ' + e.message;
      });
  }

  function applyConfig() {
    if (!currentScope || scopeReadonly) return;
    const ta = $('#configEditor');
    const res = $('#configResult');
    res.className = 'config-result';
    res.textContent = 'Saving...';
    res.style.display = 'block';

    fetch('/api/config/' + currentScope, {
      method: 'POST',
      body: ta.value
    })
      .then(r => r.json())
      .then(data => {
        if (data.error) {
          res.className = 'config-result err';
          res.textContent = 'Error: ' + data.error;
        } else {
          res.className = 'config-result ok';
          res.textContent = 'Applied successfully! ' + (data.log ? data.log.length + ' log entries' : '');
        }
      })
      .catch(e => {
        res.className = 'config-result err';
        res.textContent = 'Request failed: ' + e.message;
      });
  }

  /* ---------- log ---------- */
  let logEntries = [];

  function renderLog(entries) {
    const container = $('#logContainer');
    if (!container) return;
    logEntries = entries || logEntries;
    if (logEntries.length === 0) {
      container.innerHTML = '<div class="log-empty">Waiting for events...</div>';
      return;
    }
    container.innerHTML = logEntries.map(line => {
      const match = line.match(/^\[WEB\]\s+(\S+)\s+(.*)$/);
      if (match) {
        return '<div class="log-entry"><span class="time">[' + escapeHtml(match[1]) + ']</span> <span class="msg">' + escapeHtml(match[2]) + '</span></div>';
      }
      return '<div class="log-entry">' + escapeHtml(line) + '</div>';
    }).join('');
    container.scrollTop = container.scrollHeight;
  }

  /* ---------- SSE ---------- */
  function connectSSE() {
    const es = new EventSource('/api/stream');
    es.onmessage = function (e) {
      try {
        const d = JSON.parse(e.data);
        renderOverview(d);
        renderModules(d);
        renderChatGames(d);
      } catch (x) {}
    };
    es.addEventListener('log', function (e) {
      try {
        const logs = JSON.parse(e.data);
        if (Array.isArray(logs)) renderLog(logs);
      } catch (x) {}
    });
    es.onerror = function () {
      setTimeout(connectSSE, 3000);
    };
  }

  /* ---------- wire events ---------- */
  $('#btnLoadConfig').addEventListener('click', loadConfig);
  $('#btnApplyConfig').addEventListener('click', applyConfig);

  /* ---------- boot ---------- */
  initConfigEditor();

  /* initial fetch */
  fetch('/api/stats')
    .then(r => r.json())
    .then(d => {
      renderOverview(d);
      renderModules(d);
      renderChatGames(d);
    })
    .catch(() => {});

  fetch('/api/log')
    .then(r => r.json())
    .then(renderLog)
    .catch(() => {});

  connectSSE();

})();
