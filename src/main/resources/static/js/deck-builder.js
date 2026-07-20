/**
 * デッキビルダー。
 *
 * 設計上の要点:
 *   - カードデータはサーバの /api/cards から取得する。台帳(JSON)が唯一の正であり、
 *     ビルダーはコピーを持たない
 *   - 構築中のデッキはこのページのメモリ上だけに存在し、サーバには保存しない。
 *     保存はファイル出力(ユーザーのPCが保存先)
 *   - ここでの枚数制限などのチェックは操作の補助にすぎず、
 *     対戦に使えるかどうかの最終判定はサーバのDeckValidatorが行う
 */

const FORMAT_VERSION = 1;
/** 効果が実装済みの文明。未実装文明はデッキに入れられない(入れても何も起きないため) */
const IMPLEMENTED_CIVS = ['WATER', 'FIRE', 'DARK', 'LIGHT'];

let allCards = [];
let leaders = [];
let currentPool = 'MAIN';
let typeFilter = { MINION: true, SPELL: true, WEAPON: true };

/** 構築中のデッキ: { leaderCardId, main: {cardId: count}, taboo: [cardId...] } */
let deck = { leaderCardId: null, main: {}, taboo: [] };

// ---------------------------------------------------------------
// 初期化
// ---------------------------------------------------------------

fetch('/api/cards')
    .then(res => res.json())
    .then(data => {
        allCards = data.filter(c => IMPLEMENTED_CIVS.includes(c.civilization));
        leaders = allCards.filter(c => c.type === 'LEADER');
        const select = document.getElementById('leader-select');
        select.innerHTML = leaders
            .map(l => `<option value="${l.id}">${l.civilizationName} / ${escapeHtml(l.name)}</option>`)
            .join('');
        select.onchange = onLeaderChange;
        deck.leaderCardId = leaders.length > 0 ? leaders[0].id : null;
        render();
    })
    .catch(() => setStatus('カードデータの取得に失敗しました', 'danger'));

function leaderCard() {
    return allCards.find(c => c.id === deck.leaderCardId);
}

/** リーダーを変えると文明が変わるため、デッキの中身は作り直しになる */
function onLeaderChange(event) {
    const next = event.target.value;
    const hasCards = Object.keys(deck.main).length > 0 || deck.taboo.length > 0;
    if (hasCards && !confirm('リーダーを変更すると、構築中のデッキがリセットされます。よろしいですか?')) {
        event.target.value = deck.leaderCardId;
        return;
    }
    deck = { leaderCardId: next, main: {}, taboo: [] };
    render();
}

// ---------------------------------------------------------------
// 操作
// ---------------------------------------------------------------

function switchPool(pool) {
    currentPool = pool;
    document.querySelectorAll('[data-pool]').forEach(btn =>
        btn.classList.toggle('active', btn.dataset.pool === pool));
    render();
}

function toggleType(btn) {
    typeFilter[btn.dataset.type] = !typeFilter[btn.dataset.type];
    btn.classList.toggle('on', typeFilter[btn.dataset.type]);
    render();
}

function addCard(cardId) {
    const card = allCards.find(c => c.id === cardId);
    const leader = leaderCard();
    if (!card || !leader) return;

    if (card.civilization === leader.civilization) {
        // メインデッキ: 同名4枚まで・合計40枚まで(総合ルール1-2)
        const total = mainTotal();
        if (total >= 40) {
            setStatus('メインデッキは40枚までです', 'warning');
            return;
        }
        const count = deck.main[cardId] || 0;
        if (count >= 4) {
            setStatus('同名カードは4枚までです', 'warning');
            return;
        }
        deck.main[cardId] = count + 1;
    } else {
        // 禁忌デッキ: 8枚・同名1枚まで(総合ルール1-3)
        if (deck.taboo.length >= 8) {
            setStatus('禁忌デッキは8枚までです', 'warning');
            return;
        }
        if (deck.taboo.includes(cardId)) {
            setStatus('禁忌デッキは同名カード1枚までです', 'warning');
            return;
        }
        deck.taboo.push(cardId);
    }
    render();
}

function removeCard(cardId) {
    if (deck.main[cardId]) {
        deck.main[cardId] -= 1;
        if (deck.main[cardId] <= 0) delete deck.main[cardId];
    } else {
        deck.taboo = deck.taboo.filter(id => id !== cardId);
    }
    render();
}

function mainTotal() {
    return Object.values(deck.main).reduce((sum, n) => sum + n, 0);
}

// ---------------------------------------------------------------
// 入出力
// ---------------------------------------------------------------

function exportDeck() {
    const name = document.getElementById('deck-name').value.trim() || 'MyDeck';
    const total = mainTotal();
    if (total !== 40 || deck.taboo.length !== 8) {
        if (!confirm(`規定枚数を満たしていません(メイン${total}/40・禁忌${deck.taboo.length}/8)。\n`
            + 'このまま出力しますか? この状態では対戦に使えません。')) {
            return;
        }
    }
    const data = {
        formatVersion: FORMAT_VERSION,
        name: name,
        leaderCardId: deck.leaderCardId,
        main: Object.entries(deck.main).map(([cardId, count]) => ({ cardId, count })),
        taboo: deck.taboo,
    };
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${name}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
    setStatus(`「${name}.json」を出力しました`, 'success');
}

function importDeck(input) {
    const file = input.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
        try {
            const data = JSON.parse(reader.result);
            if (!leaders.some(l => l.id === data.leaderCardId)) {
                setStatus('このデッキのリーダーは現在選択できません(未実装文明の可能性)', 'danger');
                return;
            }
            deck = {
                leaderCardId: data.leaderCardId,
                main: Object.fromEntries((data.main || []).map(e => [e.cardId, e.count])),
                taboo: (data.taboo || []).slice(),
            };
            document.getElementById('deck-name').value = data.name || 'MyDeck';
            document.getElementById('leader-select').value = data.leaderCardId;
            render();
            setStatus('デッキを読み込みました', 'success');
        } catch (e) {
            setStatus('デッキファイルを読み込めませんでした', 'danger');
        }
    };
    reader.readAsText(file);
    input.value = '';
}

// ---------------------------------------------------------------
// 描画
// ---------------------------------------------------------------

function render() {
    const leader = leaderCard();
    if (!leader) return;

    const search = document.getElementById('search').value.trim();
    const pool = allCards.filter(c => {
        if (c.type === 'LEADER') return false;
        if (!typeFilter[c.type]) return false;
        if (search && !c.name.includes(search)) return false;
        return currentPool === 'MAIN'
            ? c.civilization === leader.civilization
            : c.civilization !== leader.civilization;
    }).sort((a, b) => (a.cost - b.cost) || a.id.localeCompare(b.id));

    document.getElementById('pool').innerHTML = pool.map(c => poolCardHtml(c, leader)).join('');
    renderDeckLists();
    renderCounts();
}

function poolCardHtml(card, leader) {
    const isMain = card.civilization === leader.civilization;
    const count = isMain ? (deck.main[card.id] || 0) : (deck.taboo.includes(card.id) ? 1 : 0);
    const limit = isMain ? 4 : 1;
    const full = count >= limit;
    const stats = card.type === 'MINION' ? `⚔${card.attack} ♥${card.hp}`
        : (card.type === 'WEAPON' ? `⚔${card.attack}` : 'スペル');
    return `
        <div class="pool-card ${card.civilization === 'FIRE' ? 'fire' : ''} ${full ? 'disabled' : ''}"
             onclick="addCard('${card.id}')" title="${escapeHtml(card.text || '')}">
            ${count > 0 ? `<div class="in-deck">${count}</div>` : ''}
            <div class="nm">(${card.cost}) ${escapeHtml(card.name)}</div>
            <div class="kw">${card.keywords.map(escapeHtml).join(' ')}</div>
            <div class="st"><span>${stats}</span></div>
        </div>`;
}

function renderDeckLists() {
    const mainRows = Object.entries(deck.main)
        .map(([cardId, count]) => ({ card: allCards.find(c => c.id === cardId), count }))
        .filter(r => r.card)
        .sort((a, b) => (a.card.cost - b.card.cost) || a.card.id.localeCompare(b.card.id));
    document.getElementById('deck-main').innerHTML = mainRows.length === 0
        ? '<div class="text-muted small p-2">カードをクリックして追加してください</div>'
        : mainRows.map(r => deckRowHtml(r.card, r.count)).join('');

    const tabooRows = deck.taboo.map(id => allCards.find(c => c.id === id)).filter(Boolean);
    document.getElementById('deck-taboo').innerHTML = tabooRows.length === 0
        ? '<div class="text-muted small p-2">リーダーと異なる文明のカードを8枚選んでください</div>'
        : tabooRows.map(c => deckRowHtml(c, 1)).join('');
}

function deckRowHtml(card, count) {
    return `
        <div class="deck-row" onclick="removeCard('${card.id}')" title="クリックで1枚減らす">
            <span class="cost">${card.cost}</span>
            <span class="nm">${escapeHtml(card.name)}</span>
            <span class="cnt">x${count}</span>
        </div>`;
}

function renderCounts() {
    const total = mainTotal();
    const mainBadge = document.getElementById('main-count');
    mainBadge.textContent = `${total} / 40`;
    mainBadge.className = 'badge ' + (total === 40 ? 'bg-success' : 'bg-danger');

    const tabooBadge = document.getElementById('taboo-count');
    tabooBadge.textContent = `${deck.taboo.length} / 8`;
    tabooBadge.className = 'badge ' + (deck.taboo.length === 8 ? 'bg-success' : 'bg-danger');

    if (total === 40 && deck.taboo.length === 8) {
        setStatus('規定枚数を満たしています。デッキファイルを出力できます', 'success');
    }
}

function setStatus(message, kind) {
    const bar = document.getElementById('status-bar');
    bar.textContent = message;
    bar.className = `alert alert-${kind || 'secondary'} py-2 mb-2 small`;
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, c => ({
        '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
    })[c]);
}
