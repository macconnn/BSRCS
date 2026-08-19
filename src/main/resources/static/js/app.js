/* =========================================================================
   線上棒球比賽紀錄表 — 共用前端邏輯
   四張畫面（編輯 PC / 檢視 PC / 編輯 Mobile / 檢視 Mobile）共用這支檔案
   ========================================================================= */
const BB = (() => {

    /* ---------------------------------------------------------- 共用常數 */

    /** 守備位置清單：新增比賽排打線 / 比賽中換人共用 */
    const POSITIONS = ['投手', '捕手', '一壘手', '二壘手', '三壘手', '游擊手', '左外野手', '中外野手', '右外野手', '指定打擊'];

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

    /* ---------------------------------------------------------- 換人（比賽進行中） */

    /**
     * 開啟換人視窗：選擇場上要換下的球員 + 該隊可用（尚未在場上）的球員 + 守備位置。
     * onFieldList：目前該隊打線（state.awayLineup 或 state.homeLineup）。
     * refresh：換人成功後用來刷新畫面的 callback，會收到最新的 game state。
     */
    async function openSubstituteModal(gameId, side, onFieldList, refresh) {
        closeSubstituteModal();

        if (!onFieldList || !onFieldList.length) {
            toast('目前沒有打線可供替換', true);
            return;
        }

        let bench;
        try {
            bench = await get(`/api/games/${gameId}/bench?side=${side}`);
        } catch (e) {
            toast(e.message, true);
            return;
        }
        if (!bench.length) {
            toast('這支球隊目前沒有可替補上場的球員', true);
            return;
        }

        // 2024 需求：換人時守備位置一律鎖死沿用「被換下」球員當下在場上守的位置，不開放挑選，
        // 也完全不會用「換上」球員自己在球隊管理登記的守備位置去決定——他只是暫時代守而已。
        // 這個欄位純粹是給記錄員看的提示文字，實際送出換人請求時後端會自己依被換下球員的位置鎖定，
        // 前端不會、也不需要把守備位置傳給後端。
        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.id = 'subModalMask';
        mask.innerHTML = `
            <div class="modal-card" role="dialog" aria-modal="true" aria-label="更換球員" style="max-width:420px;">
                <div class="modal-head">
                    <div style="font-size:17px;font-weight:700;">換人</div>
                    <button class="modal-close" aria-label="關閉">×</button>
                </div>
                <div class="modal-body" style="display:grid;gap:12px;">
                    <label><span class="field-label">被換下（目前在場上）</span>
                        <select class="input" id="subOut">
                            ${onFieldList.map(p => `<option value="${p.lineupId}">${esc(p.order)}棒　${esc(p.name)}（#${esc(p.number)}）　${esc(p.position)}</option>`).join('')}
                        </select>
                    </label>
                    <label><span class="field-label">換上（該隊可用球員）</span>
                        <select class="input" id="subIn">
                            ${bench.map(p => `<option value="${p.id}">${esc(p.name)}${p.jerseyNumber ? '（#' + esc(p.jerseyNumber) + '）' : ''}</option>`).join('')}
                        </select>
                    </label>
                    <label><span class="field-label">守備位置（自動沿用被換下球員的位置，不可更改）</span>
                        <input class="input" id="subPos" type="text" readonly disabled>
                    </label>
                    <p style="font-size:12px;color:var(--muted);margin:0;">換上的球員只是暫時代守這個位置，不會更動他在球隊管理裡登記的守備位置。</p>
                    <button class="btn btn-primary btn-block" id="subConfirm">確認換人</button>
                </div>
            </div>`;
        mask.addEventListener('click', ev => { if (ev.target === mask) closeSubstituteModal(); });
        mask.querySelector('.modal-close').addEventListener('click', closeSubstituteModal);
        document.addEventListener('keydown', subEscClose);
        document.body.appendChild(mask);

        const subOutSel = document.getElementById('subOut');
        const subPosField = document.getElementById('subPos');

        function syncSubPos() {
            const outId = parseInt(subOutSel.value, 10);
            const outPlayer = onFieldList.find(p => p.lineupId === outId);
            subPosField.value = outPlayer ? outPlayer.position : '';
        }
        subOutSel.addEventListener('change', syncSubPos);
        syncSubPos(); // 初始化：預設帶出目前選取的被換下球員之守備位置

        document.getElementById('subConfirm').addEventListener('click', async () => {
            const outLineupId = parseInt(subOutSel.value, 10);
            const inPlayerId = parseInt(document.getElementById('subIn').value, 10);
            const lockedPosition = subPosField.value; // 僅供前端提前防呆用，不會送給後端

            // 前端提前防呆：確認這個守備位置沒有被場上其他人（被換下的人除外）佔用，避免重複守備。
            // 真正把關的判斷還是在後端（依被換下球員的位置鎖定 + 重複守備檢查），這裡只是提早給提示。
            const duplicated = onFieldList.some(p => p.lineupId !== outLineupId
                && lockedPosition && p.position === lockedPosition);
            if (duplicated) {
                toast(`守備位置「${lockedPosition}」已經有球員在守備，不可重複`, true);
                return;
            }

            try {
                // 注意：故意不傳 position 給後端。守備位置一律由後端依「被換下球員」當下的位置鎖定，
                // 不會使用「換上球員」自己在球隊管理登記的守備位置。
                const data = await post(`/api/games/${gameId}/substitutions`, { side, outLineupId, inPlayerId });
                closeSubstituteModal();
                toast('已完成換人');
                if (refresh) refresh(data);
            } catch (e) {
                toast(e.message, true);
            }
        });
    }

    function subEscClose(e) { if (e.key === 'Escape') closeSubstituteModal(); }

    function closeSubstituteModal() {
        document.querySelectorAll('#subModalMask').forEach(m => m.remove());
        document.removeEventListener('keydown', subEscClose);
    }

    /* ---------------------------------------------------------- 互換守備位置（比賽進行中） */

    /**
     * 開啟互換守備位置視窗：從同一隊「目前在場上」的球員中選兩位，單純互換守備位置。
     * 不涉及換人，不影響打線棒次。
     * onFieldList：目前該隊打線（state.awayLineup 或 state.homeLineup）。
     */
    function openPositionSwapModal(gameId, side, onFieldList, refresh) {
        closePositionSwapModal();

        if (!onFieldList || onFieldList.length < 2) {
            toast('目前在場上的球員不足兩位，無法互換守備位置', true);
            return;
        }

        const optionsHtml = onFieldList.map(p =>
            `<option value="${p.lineupId}">${esc(p.order)}棒　${esc(p.name)}（#${esc(p.number)}）　${esc(p.position)}</option>`).join('');

        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.id = 'posSwapModalMask';
        mask.innerHTML = `
            <div class="modal-card" role="dialog" aria-modal="true" aria-label="互換守備位置" style="max-width:420px;">
                <div class="modal-head">
                    <div style="font-size:17px;font-weight:700;">互換守備位置</div>
                    <button class="modal-close" aria-label="關閉">×</button>
                </div>
                <div class="modal-body" style="display:grid;gap:12px;">
                    <p style="font-size:12px;color:var(--muted);margin:0;">只交換這兩位場上球員的守備位置，不會換人也不會更動打線棒次。</p>
                    <label><span class="field-label">球員 A</span>
                        <select class="input" id="posA">${optionsHtml}</select>
                    </label>
                    <label><span class="field-label">球員 B</span>
                        <select class="input" id="posB">${optionsHtml}</select>
                    </label>
                    <button class="btn btn-primary btn-block" id="posSwapConfirm">確認互換</button>
                </div>
            </div>`;
        mask.addEventListener('click', ev => { if (ev.target === mask) closePositionSwapModal(); });
        mask.querySelector('.modal-close').addEventListener('click', closePositionSwapModal);
        document.addEventListener('keydown', posSwapEscClose);
        document.body.appendChild(mask);

        const bSel = document.getElementById('posB');
        if (bSel.options.length > 1) bSel.selectedIndex = 1; // 預設選第二位，避免一開始 A/B 相同

        document.getElementById('posSwapConfirm').addEventListener('click', async () => {
            const lineupIdA = parseInt(document.getElementById('posA').value, 10);
            const lineupIdB = parseInt(document.getElementById('posB').value, 10);
            if (lineupIdA === lineupIdB) {
                toast('請選擇兩位不同的球員', true);
                return;
            }
            try {
                const data = await post(`/api/games/${gameId}/position-swap`, { side, lineupIdA, lineupIdB });
                closePositionSwapModal();
                toast('已互換守備位置');
                if (refresh) refresh(data);
            } catch (e) {
                toast(e.message, true);
            }
        });
    }

    function posSwapEscClose(e) { if (e.key === 'Escape') closePositionSwapModal(); }

    function closePositionSwapModal() {
        document.querySelectorAll('#posSwapModalMask').forEach(m => m.remove());
        document.removeEventListener('keydown', posSwapEscClose);
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
        POSITIONS,
        request, get, post, toast, esc, loadState, poll, run,
        renderCounts, renderBases, renderLineup, renderMiniLineup, renderBatterFoot,
        renderPitches, renderScoreboard, renderFeed, renderField, bindEditorActions,
        initPlayerLog, openPlayerModal, closePlayerModal,
        openSubstituteModal, closeSubstituteModal,
        openPositionSwapModal, closePositionSwapModal
    };
})();
