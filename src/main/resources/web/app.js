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
    if (!d) return;
    const setText = (sel, val) => { const el = $('#'+sel); if (el && val != null) el.textContent = val; };
    if (d.tps) {
      setText('tps1', (d.tps['1m'] != null ? d.tps['1m'] : 0).toFixed ? d.tps['1m'].toFixed(2) : '0.00');
      setText('tps5', '5m: ' + (d.tps['5m'] != null ? d.tps['5m'].toFixed(2) : '0.00'));
      setText('tps15', '15m: ' + (d.tps['15m'] != null ? d.tps['15m'].toFixed(2) : '0.00'));
      const tps1 = d.tps['1m'];
      const tpsCard = $('.card-tps .card-value');
      if (tpsCard && typeof tps1 === 'number') {
        tpsCard.style.color = tps1 < 18 ? 'var(--red)' : tps1 < 19.5 ? 'var(--yellow)' : 'var(--green)';
      }
    }
    if (d.players) {
      setText('players', d.players.online + '/' + d.players.max);
      setText('playersMax', 'Max: ' + d.players.max);
    }
    if (typeof d.uptime === 'number') setText('uptime', fmtUptime(d.uptime));
    if (d.heap) {
      setText('heapUsed', fmtBytes(d.heap.used) + ' / ' + fmtBytes(d.heap.total));
      setText('heapMax', 'Max: ' + fmtBytes(d.heap.max));
    }
    if (d.threads) {
      setText('threads', d.threads.count);
      setText('threadDaemon', 'Daemon: ' + d.threads.daemon);
    }
  }

  /* ---------- modules ---------- */
  const pendingToggles = new Set();
  let lastModulesSig = '';

  function renderModules(d) {
    const grid = $('#moduleGrid');
    if (!grid) return;
    const mods = d.modules || [];

    const sig = mods.map(m => m.id + ':' + (m.enabled ? 1 : 0)).join('|');
    const noPending = pendingToggles.size === 0;
    if (sig === lastModulesSig && noPending) {
      return;
    }
    lastModulesSig = sig;

    grid.innerHTML = mods.map(m => {
      const id = escapeHtml(m.id);
      const en = m.enabled;
      const canToggle = id !== 'web';
      const pending = pendingToggles.has(m.id);
      return `
      <div class="module-card" data-module="${id}">
        <div class="info">
          <div class="name">${id}</div>
          <div class="desc">${en ? 'Enabled' : 'Disabled'}</div>
        </div>
        <div class="actions">
          <span class="status ${en ? 'status-on' : 'status-off'}">${en ? 'ON' : 'OFF'}</span>
          ${canToggle ? `<button class="btn-toggle ${en ? 'on' : 'off'}" data-module="${id}"${pending ? ' disabled' : ''}>${pending ? '...' : 'Toggle'}</button>` : ''}
        </div>
      </div>`;
    }).join('');
  }

  function handleToggleClick(btn) {
    if (!btn || btn.disabled) return;
    const mid = btn.dataset.module;
    if (!mid || pendingToggles.has(mid)) return;
    pendingToggles.add(mid);
    btn.disabled = true;
    btn.textContent = '...';
    fetch('/api/module/' + mid + '/toggle', { method: 'POST' })
      .then(r => r.json())
      .then(data => {
        if (data.error) { alert('Error: ' + data.error); return; }
        lastModulesSig = '';
        if (data.log) renderLog(data.log);
      })
      .catch(e => { console.error('toggle failed', e); alert('Request failed: ' + e.message); })
      .finally(() => {
        pendingToggles.delete(mid);
        lastModulesSig = '';
        const stillThere = document.querySelector('.btn-toggle[data-module="' + mid + '"]');
        if (stillThere) {
          stillThere.disabled = false;
          stillThere.textContent = 'Toggle';
        }
      });
  }

  /* ---------- chat games ---------- */
  function renderChatGames(d) {
    if (!d) return;
    const cg = d.chatgames || {};
    const status = $('#cgStatus');
    if (!status) return;
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
    const history = Array.isArray(cg.history) ? cg.history : [];
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
    if (Array.isArray(entries)) logEntries = entries;
    if (!Array.isArray(logEntries)) logEntries = [];
    if (logEntries.length === 0) {
      container.innerHTML = '<div class="log-empty">Waiting for events...</div>';
      return;
    }
    container.innerHTML = logEntries.map(line => {
      if (typeof line !== 'string') return '';
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
  const gridEl = $('#moduleGrid');
  if (gridEl) {
    gridEl.addEventListener('click', function (ev) {
      const btn = ev.target.closest('.btn-toggle');
      if (btn && gridEl.contains(btn)) handleToggleClick(btn);
    });
  }
  const loadBtn = $('#btnLoadConfig');
  if (loadBtn) loadBtn.addEventListener('click', loadConfig);
  const applyBtn = $('#btnApplyConfig');
  if (applyBtn) applyBtn.addEventListener('click', applyConfig);

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
    .catch(e => { console.error('initial stats fetch failed', e); });

  fetch('/api/log')
    .then(r => r.json())
    .then(renderLog)
    .catch(e => { console.error('initial log fetch failed', e); });

  connectSSE();

})();
