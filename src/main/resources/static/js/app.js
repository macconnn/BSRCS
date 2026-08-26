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
    const del = (url) => request(url, 'DELETE');

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
            if (!el) return;
            const runner = bases[k]; // null 或 { lineupId, name, number }
            el.classList.toggle('on', !!runner);
            el.title = runner ? `#${runner.number} ${runner.name}` : '';
        });
    }

    /** 完整打線表格（PC 用） */
    function renderLineup(tbody, list, side) {
        if (!tbody) return;
        tbody.className = side === 'away' ? 'away' : 'home';
        tbody.innerHTML = list.map(p => `
            <tr class="${p.current ? 'current' + (side === 'home' ? ' home-row' : '') : ''}">
                <td class="order">${p.order}</td>
                <td><span class="clickable-name" data-player-log="${p.lineupId}">#${esc(p.number)} ${esc(p.name)}</span></td>
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
                <td><span class="clickable-name" data-player-log="${p.lineupId}">#${esc(p.number)} ${esc(p.name)}</span></td>
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
                    <span class="nm">#${esc(batter.number)} ${esc(batter.name)}</span>
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
                        <div style="font-size:17px;font-weight:700;">#${esc(p.number)} ${esc(p.name)}</div>
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
                            ${onFieldList.map(p => `<option value="${p.lineupId}">${esc(p.order)}棒　#${esc(p.number)} ${esc(p.name)}　${esc(p.position)}</option>`).join('')}
                        </select>
                    </label>
                    <label><span class="field-label">換上（該隊可用球員）</span>
                        <select class="input" id="subIn">
                            ${bench.map(p => `<option value="${p.id}">${p.jerseyNumber ? '#' + esc(p.jerseyNumber) + ' ' : ''}${esc(p.name)}</option>`).join('')}
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
            `<option value="${p.lineupId}">${esc(p.order)}棒　#${esc(p.number)} ${esc(p.name)}　${esc(p.position)}</option>`).join('');

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

    /* ---------------------------------------------------------- 盜壘（獨立於打席結果，不從壘包 UI 直接點擊） */

    const BASE_LABEL = { 1: '一壘', 2: '二壘', 3: '三壘', 4: '本壘' };
    const BASE_KEY = { 1: 'first', 2: 'second', 3: 'third' };

    /**
     * 開啟盜壘小面板：從目前壘包狀態選「從哪個壘包出發」→「盜上哪個壘包」→ 結果。
     * bases：state.game.bases（{ first, second, third }，每個是 null 或 { lineupId, name, number }）。
     * refresh：完成後用來刷新畫面的 callback，會收到最新的 game state。
     */
    function openStealModal(gameId, bases, refresh) {
        closeStealModal();

        const occupied = [1, 2, 3].filter(b => bases[BASE_KEY[b]]);
        if (!occupied.length) {
            toast('目前壘上沒有跑者，無法盜壘', true);
            return;
        }

        const fromOptions = occupied.map(b => {
            const r = bases[BASE_KEY[b]];
            return `<option value="${b}">${BASE_LABEL[b]}　#${esc(r.number)} ${esc(r.name)}</option>`;
        }).join('');

        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.id = 'stealModalMask';
        mask.innerHTML = `
            <div class="modal-card" role="dialog" aria-modal="true" aria-label="盜壘" style="max-width:420px;">
                <div class="modal-head">
                    <div style="font-size:17px;font-weight:700;">盜壘</div>
                    <button class="modal-close" aria-label="關閉">×</button>
                </div>
                <div class="modal-body" style="display:grid;gap:12px;">
                    <label><span class="field-label">從哪個壘包出發</span>
                        <select class="input" id="stealFrom">${fromOptions}</select>
                    </label>
                    <label><span class="field-label">結果</span>
                        <select class="input" id="stealOutcome">
                            <option value="SAFE">成功</option>
                            <option value="CAUGHT">被阻殺出局</option>
                        </select>
                    </label>
                    <div id="stealToWrap">
                        <label><span class="field-label">盜上哪個壘包</span>
                            <select class="input" id="stealTo"></select>
                        </label>
                        <label style="display:flex;align-items:center;gap:8px;margin-top:8px;">
                            <input type="checkbox" id="stealError">
                            <span style="font-size:13px;">這次推進有一段是因為守備失誤（會計入球隊失誤數）</span>
                        </label>
                    </div>
                    <button class="btn btn-primary btn-block" id="stealConfirm">確認</button>
                </div>
            </div>`;
        mask.addEventListener('click', ev => { if (ev.target === mask) closeStealModal(); });
        mask.querySelector('.modal-close').addEventListener('click', closeStealModal);
        document.addEventListener('keydown', stealEscClose);
        document.body.appendChild(mask);

        const fromSel = document.getElementById('stealFrom');
        const outcomeSel = document.getElementById('stealOutcome');
        const toSel = document.getElementById('stealTo');
        const toWrap = document.getElementById('stealToWrap');

        function syncToOptions() {
            const from = parseInt(fromSel.value, 10);
            const opts = [];
            for (let b = from + 1; b <= 4; b++) opts.push(`<option value="${b}">${BASE_LABEL[b]}</option>`);
            toSel.innerHTML = opts.join('');
        }
        function syncOutcomeUi() {
            toWrap.style.display = outcomeSel.value === 'CAUGHT' ? 'none' : '';
        }
        fromSel.addEventListener('change', syncToOptions);
        outcomeSel.addEventListener('change', syncOutcomeUi);
        syncToOptions();
        syncOutcomeUi();

        document.getElementById('stealConfirm').addEventListener('click', async () => {
            const fromBase = parseInt(fromSel.value, 10);
            const outcome = outcomeSel.value;
            const toBase = outcome === 'CAUGHT' ? null : parseInt(toSel.value, 10);
            const error = outcome === 'CAUGHT' ? false : document.getElementById('stealError').checked;
            try {
                const data = await post(`/api/games/${gameId}/steal`, { fromBase, toBase, outcome, error });
                closeStealModal();
                toast('已記錄盜壘');
                if (refresh) refresh(data);
            } catch (e) {
                toast(e.message, true);
            }
        });
    }

    function stealEscClose(e) { if (e.key === 'Escape') closeStealModal(); }

    function closeStealModal() {
        document.querySelectorAll('#stealModalMask').forEach(m => m.remove());
        document.removeEventListener('keydown', stealEscClose);
    }

    /* ---------------------------------------------------------- 加碼失誤推進（安打／出局結果之後的延伸失誤） */

    /**
     * 開啟加碼失誤推進面板：從目前壘上的跑者選一位，選要多推進到哪個壘包。
     * 用於安打／出局已經照正常規則推進完之後，因為守備失誤又多跑出來的壘包。
     * 只會多算一次球隊失誤、視情況加分，不算安打也不算打點。
     * bases：state.game.bases。refresh：完成後用來刷新畫面的 callback。
     */
    function openErrorAdvanceModal(gameId, bases, refresh) {
        closeErrorAdvanceModal();

        const occupied = [1, 2, 3].filter(b => bases[BASE_KEY[b]]);
        if (!occupied.length) {
            toast('目前壘上沒有跑者，無法記錄失誤推進', true);
            return;
        }

        const fromOptions = occupied.map(b => {
            const r = bases[BASE_KEY[b]];
            return `<option value="${b}">${BASE_LABEL[b]}　#${esc(r.number)} ${esc(r.name)}</option>`;
        }).join('');

        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.id = 'errorAdvanceModalMask';
        mask.innerHTML = `
            <div class="modal-card" role="dialog" aria-modal="true" aria-label="加碼失誤推進" style="max-width:420px;">
                <div class="modal-head">
                    <div style="font-size:17px;font-weight:700;">加碼失誤推進</div>
                    <button class="modal-close" aria-label="關閉">×</button>
                </div>
                <div class="modal-body" style="display:grid;gap:12px;">
                    <p style="font-size:12px;color:var(--muted);margin:0;">用於安打／出局之後，因為守備失誤讓跑者又多推進一個以上壘包的情況（例如二壘安打接傳球失誤，跑者從二壘多跑上三壘）。會計入一次球隊失誤，但不算安打、不算打點。</p>
                    <label><span class="field-label">哪位跑者</span>
                        <select class="input" id="errAdvFrom">${fromOptions}</select>
                    </label>
                    <label><span class="field-label">多推進到哪個壘包</span>
                        <select class="input" id="errAdvTo"></select>
                    </label>
                    <button class="btn btn-primary btn-block" id="errAdvConfirm">確認</button>
                </div>
            </div>`;
        mask.addEventListener('click', ev => { if (ev.target === mask) closeErrorAdvanceModal(); });
        mask.querySelector('.modal-close').addEventListener('click', closeErrorAdvanceModal);
        document.addEventListener('keydown', errorAdvanceEscClose);
        document.body.appendChild(mask);

        const fromSel = document.getElementById('errAdvFrom');
        const toSel = document.getElementById('errAdvTo');

        function syncToOptions() {
            const from = parseInt(fromSel.value, 10);
            const opts = [];
            for (let b = from + 1; b <= 4; b++) opts.push(`<option value="${b}">${BASE_LABEL[b]}</option>`);
            toSel.innerHTML = opts.join('');
        }
        fromSel.addEventListener('change', syncToOptions);
        syncToOptions();

        document.getElementById('errAdvConfirm').addEventListener('click', async () => {
            const fromBase = parseInt(fromSel.value, 10);
            const toBase = parseInt(toSel.value, 10);
            try {
                const data = await post(`/api/games/${gameId}/error-advance`, { fromBase, toBase });
                closeErrorAdvanceModal();
                toast('已記錄失誤推進');
                if (refresh) refresh(data);
            } catch (e) {
                toast(e.message, true);
            }
        });
    }

    function errorAdvanceEscClose(e) { if (e.key === 'Escape') closeErrorAdvanceModal(); }

    function closeErrorAdvanceModal() {
        document.querySelectorAll('#errorAdvanceModalMask').forEach(m => m.remove());
        document.removeEventListener('keydown', errorAdvanceEscClose);
    }

    /* ---------------------------------------------------------- 編輯壘包（不可預期狀況下的手動控制） */

    /**
     * 開啟編輯壘包面板：直接指定三個壘包各是誰（或無人），用於現有規則涵蓋不到的特殊狀況。
     * bases：state.game.bases。battingList：目前進攻方的打線（state.awayLineup 或 state.homeLineup）。
     */
    function openBaseEditModal(gameId, bases, battingList, refresh) {
        closeBaseEditModal();

        if (!battingList || !battingList.length) {
            toast('目前沒有打線可供選擇', true);
            return;
        }

        function optionsFor(currentLineupId) {
            let html = `<option value="">（無人）</option>`;
            html += battingList.map(p => `<option value="${p.lineupId}" ${p.lineupId === currentLineupId ? 'selected' : ''}>
                ${esc(p.order)}棒　#${esc(p.number)} ${esc(p.name)}</option>`).join('');
            return html;
        }

        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.id = 'baseEditModalMask';
        mask.innerHTML = `
            <div class="modal-card" role="dialog" aria-modal="true" aria-label="編輯壘包" style="max-width:420px;">
                <div class="modal-head">
                    <div style="font-size:17px;font-weight:700;">編輯壘包</div>
                    <button class="modal-close" aria-label="關閉">×</button>
                </div>
                <div class="modal-body" style="display:grid;gap:12px;">
                    <p style="font-size:12px;color:var(--muted);margin:0;">用於場上發生現有功能無法涵蓋的特殊狀況時，直接手動指定壘包狀態，請謹慎使用。</p>
                    <label><span class="field-label">一壘</span>
                        <select class="input" id="baseEditFirst">${optionsFor(bases.first ? bases.first.lineupId : null)}</select>
                    </label>
                    <label><span class="field-label">二壘</span>
                        <select class="input" id="baseEditSecond">${optionsFor(bases.second ? bases.second.lineupId : null)}</select>
                    </label>
                    <label><span class="field-label">三壘</span>
                        <select class="input" id="baseEditThird">${optionsFor(bases.third ? bases.third.lineupId : null)}</select>
                    </label>
                    <button class="btn btn-primary btn-block" id="baseEditConfirm">確認更新</button>
                </div>
            </div>`;
        mask.addEventListener('click', ev => { if (ev.target === mask) closeBaseEditModal(); });
        mask.querySelector('.modal-close').addEventListener('click', closeBaseEditModal);
        document.addEventListener('keydown', baseEditEscClose);
        document.body.appendChild(mask);

        document.getElementById('baseEditConfirm').addEventListener('click', async () => {
            const firstVal = document.getElementById('baseEditFirst').value;
            const secondVal = document.getElementById('baseEditSecond').value;
            const thirdVal = document.getElementById('baseEditThird').value;
            const runnerFirst = firstVal ? parseInt(firstVal, 10) : null;
            const runnerSecond = secondVal ? parseInt(secondVal, 10) : null;
            const runnerThird = thirdVal ? parseInt(thirdVal, 10) : null;

            const picked = [runnerFirst, runnerSecond, runnerThird].filter(v => v !== null);
            if (new Set(picked).size !== picked.length) {
                toast('同一位球員不能同時站在兩個壘包', true);
                return;
            }

            try {
                const data = await post(`/api/games/${gameId}/bases`, { runnerFirst, runnerSecond, runnerThird });
                closeBaseEditModal();
                toast('已更新壘包狀態');
                if (refresh) refresh(data);
            } catch (e) {
                toast(e.message, true);
            }
        });
    }

    function baseEditEscClose(e) { if (e.key === 'Escape') closeBaseEditModal(); }

    function closeBaseEditModal() {
        document.querySelectorAll('#baseEditModalMask').forEach(m => m.remove());
        document.removeEventListener('keydown', baseEditEscClose);
    }

    /* ---------------------------------------------------------- 編輯比分（修正手誤，逐局編輯，總分自動同步） */

    /**
     * 開啟編輯比分面板：選隊伍 + 選局數 + 輸入該局分數，用來修正手誤造成的比分錯誤。
     * 送出後後端會自動把該隊總分重算成「每一局分數的加總」，確保總分跟每局分數永遠對得起來。
     * awayName / homeName：顯示用的隊名。innings：state.scoreboard.innings（{ inning, away, home, current }，
     * away/home 是字串，尚未打過該局時是 "-"）。
     */
    function openScoreEditModal(gameId, awayName, homeName, innings, refresh) {
        closeScoreEditModal();

        if (!innings || !innings.length) {
            toast('目前沒有局數資料可供編輯', true);
            return;
        }

        const inningOptions = innings.map(i => `<option value="${i.inning}">第 ${i.inning} 局</option>`).join('');

        const mask = document.createElement('div');
        mask.className = 'modal-mask';
        mask.id = 'scoreEditModalMask';
        mask.innerHTML = `
            <div class="modal-card" role="dialog" aria-modal="true" aria-label="編輯比分" style="max-width:380px;">
                <div class="modal-head">
                    <div style="font-size:17px;font-weight:700;">編輯比分</div>
                    <button class="modal-close" aria-label="關閉">×</button>
                </div>
                <div class="modal-body" style="display:grid;gap:12px;">
                    <p style="font-size:12px;color:var(--muted);margin:0;">修正某一局的得分，送出後總分會自動重算成每局分數的加總，確保總分跟每局分數對得起來。</p>
                    <label><span class="field-label">隊伍</span>
                        <select class="input" id="scoreEditSide">
                            <option value="AWAY">${esc(awayName)}（客隊）</option>
                            <option value="HOME">${esc(homeName)}（主隊）</option>
                        </select>
                    </label>
                    <label><span class="field-label">局數</span>
                        <select class="input" id="scoreEditInning">${inningOptions}</select>
                    </label>
                    <label><span class="field-label">該局得分</span>
                        <input class="input" id="scoreEditRuns" type="number" min="0" step="1" value="0">
                    </label>
                    <button class="btn btn-primary btn-block" id="scoreEditConfirm">確認更新</button>
                </div>
            </div>`;
        mask.addEventListener('click', ev => { if (ev.target === mask) closeScoreEditModal(); });
        mask.querySelector('.modal-close').addEventListener('click', closeScoreEditModal);
        document.addEventListener('keydown', scoreEditEscClose);
        document.body.appendChild(mask);

        const sideSel = document.getElementById('scoreEditSide');
        const inningSel = document.getElementById('scoreEditInning');
        const runsInput = document.getElementById('scoreEditRuns');

        function syncRuns() {
            const inning = parseInt(inningSel.value, 10);
            const row = innings.find(i => i.inning === inning);
            const raw = row ? (sideSel.value === 'AWAY' ? row.away : row.home) : '-';
            runsInput.value = raw === '-' ? 0 : raw;
        }
        sideSel.addEventListener('change', syncRuns);
        inningSel.addEventListener('change', syncRuns);
        syncRuns();

        document.getElementById('scoreEditConfirm').addEventListener('click', async () => {
            const side = sideSel.value;
            const inning = parseInt(inningSel.value, 10);
            const runs = parseInt(runsInput.value, 10);
            if (isNaN(runs) || runs < 0) {
                toast('請輸入不小於 0 的分數', true);
                return;
            }
            try {
                const data = await post(`/api/games/${gameId}/score`, { side, inning, runs });
                closeScoreEditModal();
                toast('已更新比分');
                if (refresh) refresh(data);
            } catch (e) {
                toast(e.message, true);
            }
        });
    }

    function scoreEditEscClose(e) { if (e.key === 'Escape') closeScoreEditModal(); }

    function closeScoreEditModal() {
        document.querySelectorAll('#scoreEditModalMask').forEach(m => m.remove());
        document.removeEventListener('keydown', scoreEditEscClose);
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
        request, get, post, del, toast, esc, loadState, poll, run,
        renderCounts, renderBases, renderLineup, renderMiniLineup, renderBatterFoot,
        renderPitches, renderScoreboard, renderFeed, renderField, bindEditorActions,
        initPlayerLog, openPlayerModal, closePlayerModal,
        openSubstituteModal, closeSubstituteModal,
        openPositionSwapModal, closePositionSwapModal,
        openStealModal, closeStealModal,
        openErrorAdvanceModal, closeErrorAdvanceModal,
        openBaseEditModal, closeBaseEditModal,
        openScoreEditModal, closeScoreEditModal
    };
})();
