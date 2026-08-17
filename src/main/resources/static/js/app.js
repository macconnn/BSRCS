/* =========================================================================
   線上棒球比賽紀錄表 — 共用前端邏輯
   四張畫面（編輯 PC / 檢視 PC / 編輯 Mobile / 檢視 Mobile）共用這支檔案
   ========================================================================= */
const BB = (() => {

    /* ---------------------------------------------------------- 基礎工具 */

    async function request(url, method = 'GET', body) {
        const res = await fetch(url, {
            method,
            headers: { 'Content-Type': 'application/json' },
            body: body ? JSON.stringify(body) : undefined,
            credentials: 'same-origin'
        });
        let json;
        try { json = await res.json(); } catch (e) { json = { success: false, message: '伺服器回應格式錯誤' }; }
        if (!res.ok || json.success === false) {
            throw new Error(json.message || ('操作失敗（HTTP ' + res.status + '）'));
        }
        return json.data;
    }

    const get = (url) => request(url, 'GET');
    const post = (url, body) => request(url, 'POST', body);

    function toast(message, isError = false) {
        let el = document.querySelector('.toast');
        if (!el) {
            el = document.createElement('div');
            el.className = 'toast';
            document.body.appendChild(el);
        }
        el.textContent = message;
        el.classList.toggle('error', isError);
        el.classList.add('show');
        clearTimeout(el._timer);
        el._timer = setTimeout(() => el.classList.remove('show'), 2400);
    }

    function esc(s) {
        return String(s == null ? '' : s).replace(/[&<>"']/g, c =>
            ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    /* ---------------------------------------------------------- 狀態輪詢 */

    function loadState(gameId) {
        return get('/api/games/' + gameId + '/state');
    }

    /** 檢視模式每 10 秒自動更新；編輯模式動作後即時更新，另每 30 秒同步一次 */
    function poll(gameId, onData, intervalMs = 10000) {
        let lastAt = Date.now();
        const tick = async () => {
            try {
                const data = await loadState(gameId);
                lastAt = Date.now();
                onData(data);
                const el = document.querySelector('[data-refresh-label]');
                if (el) el.textContent = '自動更新：剛剛';
            } catch (e) {
                console.warn('狀態更新失敗', e);
            }
        };
        tick();
        setInterval(tick, intervalMs);
        setInterval(() => {
            const el = document.querySelector('[data-refresh-label]');
            if (el) {
                const sec = Math.round((Date.now() - lastAt) / 1000);
                el.textContent = '自動更新：' + sec + ' 秒前';
            }
        }, 1000);
        return tick;
    }

    /* ---------------------------------------------------------- 畫面繪製 */

    function dots(count, max, cls) {
        let html = '';
        for (let i = 0; i < max; i++) {
            html += '<span class="dot' + (i < count ? ' ' + cls : '') + '"></span>';
        }
        return html;
    }

    function renderCounts(root, g) {
        const strikeEl = root.querySelector('[data-dots-strike]');
        const ballEl = root.querySelector('[data-dots-ball]');
        const outEl = root.querySelector('[data-dots-out]');
        if (strikeEl) strikeEl.innerHTML = dots(g.strikes, 3, 'on-strike');
        if (ballEl) ballEl.innerHTML = dots(g.balls, 3, 'on-ball');
        if (outEl) outEl.innerHTML = dots(g.outs, 3, 'on-out');
    }

    function renderBases(root, bases) {
        const map = { first: '[data-base-first]', second: '[data-base-second]', third: '[data-base-third]' };
        Object.keys(map).forEach(k => {
            const el = root.querySelector(map[k]);
            if (el) el.classList.toggle('on', !!bases[k]);
        });
    }

    /** 完整打線表格（PC 用） */
    function renderLineup(tbody, list, side) {
        if (!tbody) return;
        tbody.className = side === 'away' ? 'away' : 'home';
        tbody.innerHTML = list.map(p => `
            <tr class="${p.current ? 'current' + (side === 'home' ? ' home-row' : '') : ''}">
                <td class="order">${p.order}</td>
                <td>${esc(p.number)}</td>
                <td><span class="clickable-name" data-player-log="${p.lineupId}">${esc(p.name)}</span></td>
                <td>${esc(p.position)}</td>
                <td class="avg">${esc(p.avg)}</td>
            </tr>`).join('');
    }

    /** 精簡打線（Mobile 檢視用） */
    function renderMiniLineup(tbody, list, side) {
        if (!tbody) return;
        tbody.className = side === 'away' ? 'away' : 'home';
        tbody.innerHTML = list.map(p => `
            <tr class="${p.current ? 'current' + (side === 'home' ? ' home-row' : '') : ''}">
                <td class="order">${p.order}</td>
                <td><span class="clickable-name" data-player-log="${p.lineupId}">${esc(p.name)}</span></td>
            </tr>`).join('');
    }

    function renderBatterFoot(root, batter, label, side, caption) {
        if (!root) return;
        if (!batter) { root.innerHTML = '<div class="empty">尚未設定打線</div>'; return; }
        root.innerHTML = `
            <div class="who">
                <span class="chip ${side === 'home' ? 'home' : ''}">${batter.order}</span>
                <span>
                    ${caption ? `<div class="pos">${esc(caption)}</div>` : ''}
                    <span class="nm">${esc(batter.name)}（#${esc(batter.number)}）</span>
                    <span class="pos">${esc(batter.position)}</span>
                </span>
            </div>
            <div class="today">
                <div class="lbl">${label}</div>
                <div class="val">${esc(batter.today)}</div>
            </div>`;
    }

    function renderPitches(container, pitches) {
        if (!container) return;
        if (!pitches.length) {
            container.innerHTML = '<div class="empty">本打席尚無投球紀錄</div>';
            return;
        }
        const tag = c => c === 'STRIKE' ? 'tag-strike' : c === 'BALL' ? 'tag-ball' : 'tag-foul';
        container.innerHTML = pitches.map(p => `
            <div class="pitch-row">
                <span class="no">${p.seq}</span>
                <span class="pitch-tag ${tag(p.call)}">${esc(p.callLabel)}</span>
                <span>${esc(p.pitchType)}</span>
                <span class="speed">${esc(p.speed)}</span>
                <span class="cnt">${esc(p.count)}</span>
            </div>`).join('');
    }

    function renderScoreboard(container, data) {
        if (!container) return;
        const sb = data.scoreboard;
        const head = sb.innings.map(i =>
            `<th class="${i.current ? 'now' : ''}">${i.inning}</th>`).join('');
        const row = (side, cls, total) => `
            <tr>
                <td class="team ${cls}">${esc(side)}</td>
                ${sb.innings.map(i => `<td class="${i.current ? 'now' : ''}">${cls === 'away' ? i.away : i.home}</td>`).join('')}
                <td>${total.r}</td><td>${total.h}</td><td>${total.e}</td>
            </tr>`;
        container.innerHTML = `
            <table>
                <thead><tr><th class="team">隊伍</th>${head}<th>R</th><th>H</th><th>E</th></tr></thead>
                <tbody>
                    ${row(data.away.name, 'away', sb.awayTotal)}
                    ${row(data.home.name, 'home', sb.homeTotal)}
                </tbody>
            </table>`;
    }

    function renderFeed(container, events) {
        if (!container) return;
        if (!events.length) {
            container.innerHTML = '<div class="empty">尚未有賽況紀錄</div>';
            return;
        }
        container.innerHTML = events.map(e => `
            <div class="feed-row">
                <span class="feed-dot dot-${esc(e.color)}"></span>
                <span class="when">${esc(e.inningLabel)}</span>
                <span>${esc(e.player)}</span>
                <span>${esc(e.description)}</span>
            </div>`).join('');
    }

    /** 守備陣型九人站位（百分比座標） */
    const FIELD_POS = {
        '投手': [50, 62], '捕手': [50, 89],
        '一壘手': [70, 59], '二壘手': [62, 44], '三壘手': [30, 59], '游擊手': [38, 44],
        '左外野手': [17, 30], '中外野手': [50, 17], '右外野手': [83, 30]
    };

    function renderField(container, defense) {
        if (!container) return;
        const chips = defense.filter(d => FIELD_POS[d.position]).map(d => {
            const [x, y] = FIELD_POS[d.position];
            return `<div class="pos-chip" style="left:${x}%;top:${y}%">
                        <div class="pos-name">${esc(d.position)}</div>
                        <div class="pos-player"><span class="num">${esc(d.number)}</span>${esc(d.name)}</div>
                    </div>`;
        }).join('');
        container.innerHTML = `
            <div class="field-inner">
                <svg viewBox="0 0 100 78" preserveAspectRatio="none" aria-hidden="true">
                    <polygon points="50,74 12,36 50,4 88,36" fill="#c8a97b" opacity="0.85"></polygon>
                    <polygon points="50,74 28,52 50,30 72,52" fill="#d8b98a"></polygon>
                    <polyline points="50,74 88,36" fill="none" stroke="#ffffff" stroke-width="0.6"></polyline>
                    <polyline points="50,74 12,36" fill="none" stroke="#ffffff" stroke-width="0.6"></polyline>
                    <rect x="48.6" y="50.4" width="2.8" height="2.8" fill="#ffffff" transform="rotate(45 50 51.8)"></rect>
                    <rect x="70.6" y="50.4" width="2.8" height="2.8" fill="#ffffff" transform="rotate(45 72 51.8)"></rect>
                    <rect x="26.6" y="50.4" width="2.8" height="2.8" fill="#ffffff" transform="rotate(45 28 51.8)"></rect>
                    <rect x="48.6" y="28.6" width="2.8" height="2.8" fill="#ffffff" transform="rotate(45 50 30) "></rect>
                </svg>
                ${chips}
            </div>`;
    }

    /* ---------------------------------------------------------- 球員本場表現 */

    /** 點擊打線中的球員姓名 → 顯示本場每個打席的好壞球與結果 */
    function initPlayerLog(gameId) {
        document.addEventListener('click', async (e) => {
            const el = e.target.closest('[data-player-log]');
            if (!el) return;
            e.preventDefault();
            try {
                const data = await get(`/api/games/${gameId}/lineups/${el.dataset.playerLog}/log`);
                openPlayerModal(data);
            } catch (err) {
                toast(err.message, true);
            }
        });
    }

    function openPlayerModal(data) {
        closePlayerModal();
        const p = data.player;
        const s = data.summary;

        const ballClass = c => c === 'STRIKE' ? 'pb-s' : c === 'BALL' ? 'pb-b' : 'pb-f';
        const ballText = c => c === 'STRIKE' ? 'S' : c === 'BALL' ? 'B' : 'F';

        const atBats = data.atBats.length ? data.atBats.map(ab => `
            <div class="ab-card">
                <div class="ab-head">
                    <span><strong>第 ${ab.seqNo} 打席</strong>　<span style="color:var(--muted)">${esc(ab.inningLabel)}</span></span>
                    <span class="ab-result res-${esc(ab.resultColor)}">${esc(ab.resultLabel)}${ab.rbi > 0 ? '　' + ab.rbi + ' 打點' : ''}</span>
                </div>
                ${ab.pitches.length ? `
                    <div class="pitch-seq">
                        ${ab.pitches.map(pt => `<span class="pitch-ball ${ballClass(pt.call)}" title="${esc(pt.callLabel)}">${ballText(pt.call)}</span>`).join('')}
                    </div>
                    <div class="pitch-detail">
                        ${ab.pitches.map(pt => `第 ${pt.seq} 球　${esc(pt.callLabel)}　${esc(pt.pitchType)}　${esc(pt.speed)}　球數 ${esc(pt.count)}`).join('<br>')}
                    </div>` : '<div class="pitch-detail">本打席沒有逐球紀錄</div>'}
            </div>`).join('') : '<div class="empty">本場尚未有這位球員的打席紀錄</div>';

        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.innerHTML = `
            <div class="modal-card" role="dialog" aria-modal="true" aria-label="球員本場表現">
                <div class="modal-head">
                    <div>
                        <div style="font-size:17px;font-weight:700;">${esc(p.name)}（#${esc(p.number)}）</div>
                        <div style="font-size:12px;color:var(--muted);margin-top:2px;">
                            ${esc(data.teamName)}　第 ${p.order} 棒　${esc(p.position)}　賽前打擊率 ${esc(p.avg)}
                        </div>
                    </div>
                    <button class="modal-close" aria-label="關閉">×</button>
                </div>
                <div class="modal-body">
                    <div class="stat-row">
                        <div class="stat-box"><div class="v">${esc(s.today)}</div><div class="k">打數-安打</div></div>
                        <div class="stat-box"><div class="v">${s.rbi}</div><div class="k">打點</div></div>
                        <div class="stat-box"><div class="v">${s.walks}</div><div class="k">保送</div></div>
                        <div class="stat-box"><div class="v">${s.strikeouts}</div><div class="k">三振</div></div>
                    </div>
                    ${atBats}
                </div>
            </div>`;
        mask.addEventListener('click', ev => { if (ev.target === mask) closePlayerModal(); });
        mask.querySelector('.modal-close').addEventListener('click', closePlayerModal);
        document.addEventListener('keydown', escClose);
        document.body.appendChild(mask);
    }

    function escClose(e) { if (e.key === 'Escape') closePlayerModal(); }

    function closePlayerModal() {
        document.querySelectorAll('.modal-mask').forEach(m => m.remove());
        document.removeEventListener('keydown', escClose);
    }

    /* ---------------------------------------------------------- 編輯動作 */

    function bindEditorActions(gameId, refresh) {
        document.querySelectorAll('[data-pitch]').forEach(btn => {
            btn.addEventListener('click', async () => {
                const body = {
                    call: btn.dataset.pitch,
                    pitchType: document.querySelector('[data-pitch-type]')?.value || null,
                    speedKmh: parseInt(document.querySelector('[data-pitch-speed]')?.value, 10) || null
                };
                await run(() => post(`/api/games/${gameId}/pitches`, body), refresh);
            });
        });

        document.querySelectorAll('[data-result]').forEach(btn => {
            btn.addEventListener('click', () =>
                run(() => post(`/api/games/${gameId}/results`, { result: btn.dataset.result }), refresh, btn.textContent.trim()));
        });

        bind('[data-action="next"]', () => post(`/api/games/${gameId}/next-batter`), refresh);
        bind('[data-action="undo"]', () => post(`/api/games/${gameId}/undo`), refresh);
        bind('[data-action="finish"]', () => post(`/api/games/${gameId}/finish`), refresh, '比賽已結束', '確定要結束這場比賽嗎？結束後將無法再記錄。');
        bind('[data-action="reset"]', () => post(`/api/games/${gameId}/reset`), refresh, '比賽已重新開始', '重新開始會清除本場所有紀錄，確定嗎？');
        bind('[data-action="save"]', () => Promise.resolve(), refresh, '紀錄已即時儲存於伺服器');

        function bind(selector, fn, done, message, confirmText) {
            document.querySelectorAll(selector).forEach(el => el.addEventListener('click', async () => {
                if (confirmText && !confirm(confirmText)) return;
                await run(fn, done, message);
            }));
        }
    }

    async function run(fn, refresh, message) {
        try {
            const data = await fn();
            if (refresh) refresh(data);
            if (message) toast(message);
        } catch (e) {
            toast(e.message, true);
        }
    }

    return {
        request, get, post, toast, esc, loadState, poll, run,
        renderCounts, renderBases, renderLineup, renderMiniLineup, renderBatterFoot,
        renderPitches, renderScoreboard, renderFeed, renderField, bindEditorActions,
        initPlayerLog, openPlayerModal, closePlayerModal
    };
})();
