/*
 * ★★★Batch 85b: 通常モードの演出モジュール(window.QteFx)。
 *
 * 移植元は reference/card-battle/src/fx/(デモの演出モジュール・ES モジュール9本)である。
 * ★QTE は ES モジュールを使っていないので、<b>1本の IIFE に束ねて window.QteFx で公開する</b>(設計書 4-1)。
 * ★★reference/ は配信物ではない —— static/ から読み込まず、ここへ写してある。
 *
 * <h2>このファイルが知らないこと</h2>
 * ★<b>ゲームのルールも、QTE の盤面の DOM も知らない</b>(デモの fx/ と同じ約束)。
 * 受け取るのは「どの要素を・どう動かすか」だけである。
 * 出来事 → 演出の対応表は battle.js の側にある(デモの director.js に相当する)。
 *
 * <h2>デモから変えたところ</h2>
 * <ol>
 *   <li>★★<b>timing.reduced を持ち込まない。</b>演出を出してよいかの正は battle.js の
 *       fxAllowed() 1本である(設計書 1-3・裁定375)。ここは「呼ばれたら動く」だけである。</li>
 *   <li>★<b>setSpeed を持ち込まない</b>(設計書 4-3)。QTE の速さの正は stepTune() の1箇所である。</li>
 *   <li>★★<b>拍の長さを MS として公開する。</b>battle.js は同じ値を fxRegister へ渡すので、
 *       <b>段の長さはここの値に黙って追随する</b>(84b 2-1)。数字を2箇所に書かない。</li>
 *   <li>★<b>盤面の骨格(石床・金枠カード・数値珠)を作らない</b>(設計書 0章)。
 *       描くのは battle.js が用意した演出の層(position: fixed)の中だけである。</li>
 *   <li>★パーティクルの rAF は<b>粒が在るあいだだけ</b>回す(デモは常時回していた)。</li>
 *   <li>★★<b>画面揺れは transform ではなく translate 属性で掛ける。</b>
 *       演出の層は揺らす要素の外(body 直下)に在るので包含ブロックは変わらないが、
 *       盤面の transform を上書きしないためである(ホバーの持ち上げ等)。</li>
 * </ol>
 *
 * <h2>★★拍と余韻</h2>
 * {@link MS} に載せるのは<b>「次の段を始めてよくなるまで」の拍</b>である。
 * 破片の飛散・粒子・焦げ跡は<b>余韻</b>であり、段の長さに入れない ——
 * 余韻は自分で消える(破片と焦げ跡は setTimeout、粒子は寿命)。
 * ★余韻まで段の長さに入れると、破壊1体で段が 1.8 秒になる。
 */
(function () {
    'use strict';

    // =================================================================
    // 1) 拍の長さ(ms)。★battle.js はこの値をそのまま fxRegister へ渡す
    // =================================================================

    /**
     * ★★★<b>段の長さの材料である。</b>ここを変えれば段の長さも変わる(84b 2-1)。
     * ★値はデモの標準速度の値を、QTE の段に合わせて丸めたものである。
     *
     * ★★★Batch 84c(裁定378): <b>数字・破壊・消滅・詠唱の拍を詰めた</b>。
     * ★<b>拍は「次の段へ進むまでの時間」であり、見た目の長さとは別である</b> ——
     *   数字は拍が終わっても浮き続けて自分で消える({@link NUMBER_LIFE_MS})。破片・焦げ跡・粒子も余韻のまま。
     */
    const MS = Object.freeze({
        /** 攻撃: 溜め 300 → 突進 150 → ヒットストップ 80 → 戻り 420 */
        ATTACK: 950,
        /** 攻撃の着弾の瞬間(溜め + 突進) */
        ATTACK_IMPACT: 450,
        /** ダメージ・回復の数字の拍(★84c: 800 → 500。数字そのものは余韻として 800ms 浮く) */
        NUMBER: 500,
        /** 破壊: ひびと震え 520 → 砕ける(★84c: 940 → 700。破片の飛散は余韻) */
        DEATH: 700,
        /** 消滅(裁定372): 光に包まれ 300 → ほどけて昇る 400(★84c: 880 → 700) */
        BANISH: 700,
        /** 召喚の着地: 浮き上がり 260 → 溜め 200 → 叩きつけ 170 → 弾み 260 */
        SUMMON: 890,
        /** 呪文の詠唱: 詠唱位置へ 320 → 収束 440 → ほどける 140(★84c: 1200 → 900) */
        CAST: 900,
        /** 呪文の弾(詠唱位置 → 対象) */
        BOLT: 480,
        /** 帯テロップ: 出る → 保つ → 消える */
        BANNER: 1150,
    });

    /**
     * ★★数字が浮いて消えるまでの<b>見た目の長さ</b>(84c・裁定378)。
     * ★<b>段の長さには入れない</b>(余韻)—— 拍({@code MS.NUMBER})が終われば次の段へ進み、数字は浮いたまま消えていく。
     */
    const NUMBER_LIFE_MS = 800;

    // =================================================================
    // 2) 小さな道具(デモの core.js)
    // =================================================================

    const rand = (a, b) => a + Math.random() * (b - a);
    const pick = (list) => list[Math.floor(Math.random() * list.length)];
    const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
    const easeOut = (k) => 1 - (1 - k) ** 3;
    const center = (r) => ({ x: r.left + r.width / 2, y: r.top + r.height / 2 });
    const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

    /**
     * Web Animations API の薄いラッパー。終了状態を保持し、完了で解決する。
     * ★★<b>取り消されても reject しない。</b>段が捨てられたとき(部屋消失・再接続)に
     * 演出の途中で要素が外されるので、reject させると未処理の Promise が画面のエラーになる。
     */
    function anim(el, frames, ms, easing, extra) {
        const a = el.animate(frames, Object.assign({
            duration: ms, easing: easing || 'ease', fill: 'forwards',
        }, extra || {}));
        return a.finished.catch(() => null);
    }

    function clearAnims(el) {
        el.getAnimations().forEach((a) => a.cancel());
    }

    /** ★演出の本体を走らせる。★<b>途中で要素が消えても画面のエラーにしない</b>(上の anim と同じ理由) */
    function run(fn) {
        Promise.resolve().then(fn).catch(() => null);
    }

    // =================================================================
    // 3) パーティクル(デモの particles.js)
    // =================================================================

    const RUNE_PAD = 1.35;

    class Particles {
        constructor(canvas) {
            this.canvas = canvas;
            this.ctx = canvas.getContext('2d');
            this.list = [];
            this.width = 0;
            this.height = 0;
            this.running = false;
            this.last = 0;
            this.runeCache = new Map();
            this.glowCache = new Map();
            this.resize();
            addEventListener('resize', () => this.resize());
        }

        resize() {
            // ★全画面 canvas は解像度に比例して重くなるため、倍率に上限を設ける(デモのまま)
            const dpr = Math.min(1.5, window.devicePixelRatio || 1);
            this.width = this.canvas.clientWidth || window.innerWidth;
            this.height = this.canvas.clientHeight || window.innerHeight;
            this.canvas.width = this.width * dpr;
            this.canvas.height = this.height * dpr;
            if (this.ctx) this.ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        }

        /** 粒を1つ足す。★<b>止まっていれば rAF を起こす</b>(粒が無いあいだは回さない) */
        add(p) {
            if (!this.ctx) return;
            this.list.push(Object.assign({ t: 0, delay: 0 }, p));
            if (!this.running) {
                this.running = true;
                this.last = 0;
                requestAnimationFrame((t) => this.tick(t));
            }
        }

        clear() {
            this.list.length = 0;
        }

        tick(ts) {
            const dt = Math.min(40, ts - (this.last || ts));
            this.last = ts;
            const ctx = this.ctx;
            ctx.clearRect(0, 0, this.width, this.height);
            if (!this.list.length) {
                this.running = false;
                return;
            }
            for (let i = this.list.length - 1; i >= 0; i--) {
                const p = this.list[i];
                if (p.delay > 0) { p.delay -= dt; continue; }
                p.t += dt;
                const k = p.t / p.life;
                if (k >= 1) { this.list.splice(i, 1); continue; }
                DRAW[p.type](this, p, k, dt);
            }
            ctx.globalCompositeOperation = 'source-over';
            ctx.globalAlpha = 1;
            requestAnimationFrame((t) => this.tick(t));
        }

        /** 魔法陣の画像(光彩込みで一度だけ描く) */
        runeSprite(c, n) {
            const key = c + '/' + n;
            if (this.runeCache.has(key)) return this.runeCache.get(key);
            const R = 128;
            const size = Math.ceil(R * 2 * RUNE_PAD);
            const oc = document.createElement('canvas');
            oc.width = oc.height = size;
            const g = oc.getContext('2d');
            g.translate(size / 2, size / 2);
            g.strokeStyle = `hsla(${c},1)`; g.shadowColor = `hsla(${c},1)`; g.shadowBlur = 18;
            g.lineWidth = 6; g.beginPath(); g.arc(0, 0, R, 0, 7); g.stroke();
            g.lineWidth = 3; g.beginPath(); g.arc(0, 0, R * 0.8, 0, 7); g.stroke();
            g.beginPath();
            for (let i = 0; i <= n; i++) {
                const t = (i * 4 * Math.PI) / n;
                g[i ? 'lineTo' : 'moveTo'](Math.cos(t) * R * 0.8, Math.sin(t) * R * 0.8);
            }
            g.stroke();
            g.beginPath();
            for (let i = 0; i < 24; i++) {
                const t = (i / 24) * 6.283;
                const e = i % 3 ? 1.06 : 1.12;
                g.moveTo(Math.cos(t) * R, Math.sin(t) * R);
                g.lineTo(Math.cos(t) * R * e, Math.sin(t) * R * e);
            }
            g.stroke();
            this.runeCache.set(key, oc);
            return oc;
        }

        /** 光球の画像 */
        glowSprite(c) {
            if (this.glowCache.has(c)) return this.glowCache.get(c);
            const oc = document.createElement('canvas');
            oc.width = oc.height = 64;
            const g = oc.getContext('2d');
            const gr = g.createRadialGradient(32, 32, 0, 32, 32, 32);
            gr.addColorStop(0, 'rgba(255,255,255,1)');
            gr.addColorStop(0.25, `hsla(${c},.9)`);
            gr.addColorStop(1, `hsla(${c},0)`);
            g.fillStyle = gr; g.fillRect(0, 0, 64, 64);
            this.glowCache.set(c, oc);
            return oc;
        }
    }

    /** 2点間をギザギザに分割した稲妻の折れ線 */
    function jag(pts) {
        const out = [pts[0]];
        for (let i = 1; i < pts.length; i++) {
            const [x0, y0] = pts[i - 1];
            const [x1, y1] = pts[i];
            const L = Math.hypot(x1 - x0, y1 - y0) || 1;
            const n = Math.max(4, (L / 22) | 0);
            const nx = -(y1 - y0) / L;
            const ny = (x1 - x0) / L;
            for (let j = 1; j < n; j++) {
                const t = j / n;
                const off = rand(-1, 1) * L * 0.07 * Math.sin(Math.PI * t);
                out.push([x0 + (x1 - x0) * t + nx * off, y0 + (y1 - y0) * t + ny * off]);
            }
            out.push(pts[i]);
        }
        return out;
    }

    // ★色は "色相,彩度%,明度%" の文字列で受け取り、hsla() に透明度を足して使う(デモのまま)
    const DRAW = {
        dot(P, p, k, dt) {
            const ctx = P.ctx;
            const f = dt / 16;
            p.vx *= p.drag ?? 1;
            p.vy = p.vy * (p.drag ?? 1) + (p.g || 0) * f;
            p.x += p.vx * f; p.y += p.vy * f;
            const s = p.size * (p.grow ? 1 + k * p.grow : 1 - k * 0.6);
            ctx.globalCompositeOperation = p.add ? 'lighter' : 'source-over';
            ctx.fillStyle = `hsla(${p.c},${(1 - k) * (p.a ?? 1)})`;
            ctx.beginPath(); ctx.arc(p.x, p.y, s, 0, 7); ctx.fill();
        },
        spark(P, p, k, dt) {
            const ctx = P.ctx;
            const f = dt / 16;
            p.vx *= 0.96; p.vy = p.vy * 0.96 + (p.g || 0) * f;
            p.x += p.vx * f; p.y += p.vy * f;
            ctx.globalCompositeOperation = 'lighter';
            ctx.strokeStyle = `hsla(${p.c},${1 - k})`;
            ctx.lineWidth = p.size * (1 - k) + 0.5; ctx.lineCap = 'round';
            ctx.beginPath(); ctx.moveTo(p.x, p.y);
            ctx.lineTo(p.x - p.vx * p.len, p.y - p.vy * p.len); ctx.stroke();
        },
        ring(P, p, k) {
            const ctx = P.ctx;
            const r = p.r0 + (p.r1 - p.r0) * easeOut(k);
            ctx.globalCompositeOperation = 'lighter';
            ctx.strokeStyle = `hsla(${p.c},${1 - k})`; ctx.lineWidth = p.w * (1 - k) + 0.5;
            ctx.beginPath(); ctx.ellipse(p.x, p.y, r, r * (p.flat || 1), 0, 0, 7); ctx.stroke();
        },
        rune(P, p, k) {
            const ctx = P.ctx;
            const a = k < 0.2 ? k / 0.2 : k > 0.75 ? (1 - k) / 0.25 : 1;
            const r = p.r * (0.6 + 0.4 * easeOut(Math.min(1, k * 2.5)));
            const img = P.runeSprite(p.c, p.n || 6);
            const sz = r * 2 * RUNE_PAD;
            ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = a;
            ctx.save(); ctx.translate(p.x, p.y); ctx.scale(1, p.flat); ctx.rotate(p.t * 0.0025);
            ctx.drawImage(img, -sz / 2, -sz / 2, sz, sz);
            ctx.restore(); ctx.globalAlpha = 1;
        },
        gather(P, p, k) {
            const ctx = P.ctx;
            const e = k * k;
            const x = p.sx + (p.tx - p.sx) * e;
            const y = p.sy + (p.ty - p.sy) * e;
            ctx.globalCompositeOperation = 'lighter';
            ctx.fillStyle = `hsla(${p.c},${0.25 + 0.75 * k})`;
            ctx.beginPath(); ctx.arc(x, y, p.size * (1 - 0.5 * k), 0, 7); ctx.fill();
        },
        wisp(P, p, k) {
            const ctx = P.ctx;
            const x = p.x0 + Math.sin(k * 9) * p.sway * k;
            const y = p.y0 - p.rise * easeOut(k);
            const a = k < 0.15 ? k / 0.15 : 1 - (k - 0.15) / 0.85;
            const s = p.size * (1 - 0.45 * k);
            ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = a;
            ctx.drawImage(P.glowSprite(p.c), x - s, y - s, s * 2, s * 2); ctx.globalAlpha = 1;
            if (Math.random() < 0.7) {
                P.list.push({
                    type: 'dot', add: true, x: x + rand(-4, 4), y: y + rand(-4, 4),
                    vx: rand(-0.3, 0.3), vy: rand(0.2, 0.7), size: rand(1.2, 2.8), c: p.c, a: a,
                    life: rand(300, 550), t: 0, delay: 0,
                });
            }
        },
        proj(P, p, k) {
            const ctx = P.ctx;
            const e = k * k * (1.6 - 0.6 * k);
            const u = 1 - e;
            const x = u * u * p.sx + 2 * u * e * p.cx + e * e * p.tx;
            const y = u * u * p.sy + 2 * u * e * p.cy + e * e * p.ty;
            ctx.globalCompositeOperation = 'lighter';
            ctx.drawImage(P.glowSprite(p.c), x - p.size, y - p.size, p.size * 2, p.size * 2);
            ctx.drawImage(P.glowSprite('50,100%,92%'),
                x - p.size * 0.45, y - p.size * 0.45, p.size * 0.9, p.size * 0.9);
            for (let i = 0; i < 3; i++) {
                P.list.push({
                    type: 'dot', add: true, x: x + rand(-5, 5), y: y + rand(-5, 5),
                    vx: rand(-0.6, 0.6), vy: rand(-1.2, 0), drag: 0.95, size: rand(3, 7),
                    c: pick([p.c, p.c, '42,100%,65%']), life: rand(220, 420), t: 0, delay: 0,
                });
            }
        },
        bolt(P, p, k) {
            const ctx = P.ctx;
            // 経路を数十 ms ごとに引き直して明滅させる
            if (!p.path || p.t - p.last > 45) { p.last = p.t; p.path = jag(p.pts); }
            const a = k < 0.1 ? 1 : (1 - k) ** 1.5;
            ctx.globalCompositeOperation = 'lighter'; ctx.lineJoin = 'round'; ctx.lineCap = 'round';
            for (const [w, col] of [[10, `hsla(${p.c},${0.25 * a})`],
                [4, `hsla(${p.c},${0.85 * a})`], [1.6, `rgba(255,255,255,${a})`]]) {
                ctx.lineWidth = w; ctx.strokeStyle = col; ctx.beginPath();
                p.path.forEach(([x, y], i) => (i ? ctx.lineTo(x, y) : ctx.moveTo(x, y)));
                ctx.stroke();
            }
        },
        orbit(P, p, k) {
            const ctx = P.ctx;
            const ang = p.a0 + p.w * p.t;
            const r = p.r0 + (p.r1 - p.r0) * k;
            const x = p.x + Math.cos(ang) * r;
            const y = p.y + Math.sin(ang) * r * p.flat - p.rise * k;
            const a = k < 0.15 ? k / 0.15 : 1 - (k - 0.15) / 0.85;
            ctx.globalCompositeOperation = 'lighter'; ctx.globalAlpha = a;
            ctx.drawImage(P.glowSprite(p.c), x - p.size, y - p.size, p.size * 2, p.size * 2);
            ctx.globalAlpha = 1;
        },
        beam(P, p, k) {
            const ctx = P.ctx;
            const w = p.w * Math.sin(Math.PI * k);
            ctx.globalCompositeOperation = 'lighter';
            const g = ctx.createLinearGradient(p.x - w, 0, p.x + w, 0);
            g.addColorStop(0, `hsla(${p.c},0)`); g.addColorStop(0.5, `hsla(${p.c},.9)`);
            g.addColorStop(1, `hsla(${p.c},0)`);
            ctx.fillStyle = g; ctx.fillRect(p.x - w, 0, w * 2, p.y);
            ctx.fillStyle = `rgba(255,255,255,${0.8 * Math.sin(Math.PI * k)})`;
            ctx.fillRect(p.x - w * 0.12, 0, w * 0.24, p.y);
        },
        burst(P, p, k) {
            const ctx = P.ctx;
            ctx.globalCompositeOperation = 'lighter';
            ctx.strokeStyle = `hsla(${p.c},${1 - k})`; ctx.lineCap = 'round';
            p.rays.forEach(([ang, len]) => {
                const L = len * easeOut(k);
                const s = L * 0.45;
                ctx.lineWidth = 5 * (1 - k) + 0.5; ctx.beginPath();
                ctx.moveTo(p.x + Math.cos(ang) * s, p.y + Math.sin(ang) * s);
                ctx.lineTo(p.x + Math.cos(ang) * L, p.y + Math.sin(ang) * L); ctx.stroke();
            });
            const gr = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.glow);
            gr.addColorStop(0, `rgba(255,255,240,${0.9 * (1 - k)})`);
            gr.addColorStop(1, 'rgba(255,200,120,0)');
            ctx.fillStyle = gr; ctx.beginPath(); ctx.arc(p.x, p.y, p.glow, 0, 7); ctx.fill();
        },
        streak(P, p, k) {
            const ctx = P.ctx;
            ctx.globalCompositeOperation = 'lighter';
            ctx.strokeStyle = `rgba(255,240,220,${0.7 * (1 - k)})`;
            ctx.lineWidth = p.w * (1 - k) + 0.5; ctx.lineCap = 'round';
            ctx.beginPath(); ctx.moveTo(p.x1, p.y1); ctx.lineTo(p.x2, p.y2); ctx.stroke();
        },
    };

    /** ランダムな方向の光線を n 本作る(burst 用) */
    const rays = (n, min, max) => Array.from({ length: n }, () => [rand(0, 6.283), rand(min, max)]);

    // =================================================================
    // 4) 舞台(デモの stage.js)。★盤面の骨格は作らない。演出の層の中に道具だけを置く
    // =================================================================

    /**
     * 演出の層に道具(粒子の canvas・閃光・帯)を置く。★<b>何度呼んでも1組だけ</b>である。
     *
     * @param layer  battle.js の演出の層(position: fixed; inset: 0)
     * @param shakeTarget 画面揺れを掛ける要素(★盤面。演出の層そのものは揺らさない)
     */
    function mount(layer, shakeTarget) {
        if (layer.__qteStage) {
            layer.__qteStage.shakeTarget = shakeTarget || null;
            return layer.__qteStage;
        }
        const canvas = document.createElement('canvas');
        canvas.className = 'auto-fx-canvas';
        const flash = document.createElement('div');
        flash.className = 'auto-fx-flash';
        const banner = document.createElement('div');
        banner.className = 'auto-fx-banner';
        layer.appendChild(canvas);
        layer.appendChild(flash);
        layer.appendChild(banner);
        const stage = {
            layer: layer, canvas: canvas, flash: flash, banner: banner,
            shakeTarget: shakeTarget || null, particles: null,
        };
        stage.particles = new Particles(canvas);
        layer.__qteStage = stage;
        return stage;
    }

    const fxAdd = (stage, p) => stage.particles.add(p);
    const viewW = () => window.innerWidth;
    const viewH = () => window.innerHeight;

    /**
     * 画面揺れ(裁定375)。★<b>揺らすかどうかは呼ぶ側が決める</b>(閾値は battle.js)。
     * ★translate 属性で掛けるので、盤面の transform(持ち上げ・回転)を上書きしない。
     */
    function shake(stage, mag, ms) {
        const el = stage.shakeTarget;
        if (!el) return;
        const frames = [];
        for (let i = 0; i < 8; i++) {
            const m = mag * (1 - i / 8);
            frames.push({ translate: `${rand(-m, m).toFixed(1)}px ${rand(-m, m).toFixed(1)}px` });
        }
        frames.push({ translate: '0px 0px' });
        el.animate(frames, { duration: ms || 320, easing: 'linear' });
    }

    /** 画面全体の白い閃光(透明度だけを動かすので軽い) */
    function screenFlash(stage, a, ms) {
        stage.flash.animate([{ opacity: a }, { opacity: 0 }], { duration: ms, easing: 'ease-out' });
    }

    /**
     * ★画面座標 rect の上に色の膜を重ねて一瞬光らせる。
     * ★★<b>本物の盤面の要素には子を足さない</b> —— 膜は holder(演出の層)に置く。
     * 盤面の要素へ子を足すと、描画の側の規則(:last-child・position の前提)を黙って変える。
     */
    function flashAt(holder, rect, color, ms, a) {
        if (!holder || !rect || !rect.width) return;
        const o = document.createElement('div');
        o.className = 'auto-fx-flashat';
        Object.assign(o.style, { left: rect.left + 'px', top: rect.top + 'px',
            width: rect.width + 'px', height: rect.height + 'px', background: color });
        holder.appendChild(o);
        o.animate([{ opacity: a === undefined ? 0.9 : a }, { opacity: 0 }],
            { duration: ms || 300, easing: 'ease-out', fill: 'forwards' });
    }

    /** 複製(演出の層の中の要素)の上に色の膜を重ねて一瞬光らせる */
    function flashOn(el, color, ms, a) {
        if (!el) return;
        const o = document.createElement('div');
        o.className = 'auto-fx-flashov';
        o.style.background = color;
        el.appendChild(o);
        o.animate([{ opacity: a === undefined ? 0.9 : a }, { opacity: 0 }],
            { duration: ms || 300, easing: 'ease-out' }).finished
            .catch(() => null).then(() => o.remove());
    }

    /**
     * ダメージや回復の数字を要素の上に弾ませる。cls は '' / 'heal'。
     * ★★★Batch 84c(裁定378): <b>数字は余韻である</b> —— holder ではなく層の直下に置き、自分で消える。
     *   holder に置くと、拍(500ms)で holder が外れたとき<b>数字が浮き切る前に消える</b>。
     * @return 置いた要素(検証が文字と種類を読む)
     */
    function popNumber(stage, rect, text, cls) {
        const c = center(rect);
        const n = document.createElement('div');
        n.className = 'auto-fx-num' + (cls ? ' auto-fx-num-' + cls : '');
        n.textContent = text;
        n.style.left = c.x + 'px';
        n.style.top = c.y + 'px';
        stage.layer.appendChild(n);
        n.animate([
            { transform: 'translate(-50%,-50%) scale(.2)', opacity: 1 },
            { transform: 'translate(-50%,-50%) scale(1.5)', opacity: 1, offset: 0.18 },
            { transform: 'translate(-50%,-60%) scale(1)', opacity: 1, offset: 0.35 },
            { transform: 'translate(-50%,-140%) scale(.9)', opacity: 0 },
        ], { duration: NUMBER_LIFE_MS, easing: 'ease-out', fill: 'forwards' }).finished
            .catch(() => null).then(() => n.remove());
        return n;
    }

    /** 画面中央の帯テロップ */
    function banner(stage, text, cls) {
        const b = stage.banner;
        b.textContent = text;
        b.className = 'auto-fx-banner' + (cls ? ' ' + cls : '');
        b.getAnimations().forEach((a) => a.cancel());
        const hold = MS.BANNER - 500;
        const a = b.animate([
            { opacity: 0, transform: 'translateY(-50%) scaleY(.2)', letterSpacing: '.6em' },
            { opacity: 1, transform: 'translateY(-50%) scaleY(1)', letterSpacing: '.12em', offset: 0.25 },
            { opacity: 1, transform: 'translateY(-50%) scaleY(1)', letterSpacing: '.16em', offset: 0.75 },
            { opacity: 0, transform: 'translateY(-50%) scaleY(.6)', letterSpacing: '.3em' },
        ], { duration: hold + 500, easing: 'ease-out', fill: 'forwards' });
        return a.finished.catch(() => null);
    }

    function clearBanner(stage) {
        stage.banner.getAnimations().forEach((a) => a.cancel());
        stage.banner.textContent = '';
    }

    // =================================================================
    // 5) 複製(ゴースト)。★本物の盤面の要素を写して演出の層へ置く
    // =================================================================

    /**
     * 要素を写した複製を、画面座標 rect の位置に置く。
     *
     * ★★<b>id と data-instance-id を剥がす。</b>剥がさないと、演出のあいだ
     * getElementById / querySelector が<b>複製のほうを引く</b>ことがある(描画が複製に書き込む)。
     * ★大きさは rect で固定する —— 盤面の祖先に依存する寸法の規則は、層の中では効かない。
     */
    function cloneAt(holder, el, rect) {
        const c = el.cloneNode(true);
        c.removeAttribute('id');
        c.removeAttribute('data-instance-id');
        c.querySelectorAll('[id]').forEach((n) => n.removeAttribute('id'));
        c.querySelectorAll('[data-instance-id]').forEach((n) => n.removeAttribute('data-instance-id'));
        c.classList.add('auto-fx-clone');
        c.style.left = rect.left + 'px';
        c.style.top = rect.top + 'px';
        c.style.width = rect.width + 'px';
        c.style.height = rect.height + 'px';
        holder.appendChild(c);
        return c;
    }

    const rectOf = (el) => {
        const r = el.getBoundingClientRect();
        return { left: r.left, top: r.top, width: r.width, height: r.height,
            right: r.right, bottom: r.bottom };
    };

    // =================================================================
    // 6) 攻撃(デモの attack.js)。★att は層の中の複製、tgt は的(本物でも複製でもよい)
    // =================================================================

    /**
     * 溜め → 突進(残像)→ 着弾(閃光・火花)→ 戻る。
     * ★<b>tgt までまっすぐ突進する</b>。的がリーダーなら右の列まで行く(裁定371)。
     * @param o.power    演出の強さの目安(攻撃力)
     * @param o.onImpact 着弾の瞬間に呼ばれる
     */
    async function playAttack(stage, holder, att, tgt, o) {
        const opts = o || {};
        const power = opts.power || 1;
        const a = rectOf(att);
        const t = rectOf(tgt);
        const ac = center(a);
        const tc = center(t);
        const dx = tc.x - ac.x;
        const dy = tc.y - ac.y;
        const dist = Math.hypot(dx, dy) || 1;
        const ux = dx / dist;
        const uy = dy / dist;
        const travel = Math.max(0, dist - (a.height * 0.5 + t.height * 0.45));
        const tx = ux * travel;
        const ty = uy * travel;
        const tilt = ux * 12;
        att.dataset.fxTravelX = String(Math.round(tx));
        att.dataset.fxTravelY = String(Math.round(ty));
        att.classList.add('auto-fx-charging');

        // 1) 溜め
        await anim(att, [
            { transform: 'translate(0,0) scale(1)' },
            { transform: `translate(${-ux * 22}px,${-uy * 22}px) scale(1.14) rotate(${-tilt * 0.6}deg)` },
        ], 300, 'cubic-bezier(.2,.8,.3,1)');

        // 2) 突進と残像
        for (let i = 0; i < 9; i++) {
            const s = i / 9;
            const ox = rand(-0.35, 0.35) * a.width;
            const x = ac.x + ux * travel * s - uy * ox;
            const y = ac.y + uy * travel * s + ux * ox;
            fxAdd(stage, { type: 'streak', x1: x, y1: y, x2: x - ux * a.height * 0.6,
                y2: y - uy * a.height * 0.6, w: 3, life: 260, delay: s * 120 });
        }
        await anim(att, [
            { transform: `translate(${-ux * 22}px,${-uy * 22}px) scale(1.14) rotate(${-tilt * 0.6}deg)` },
            { transform: `translate(${tx}px,${ty}px) scale(1.14) rotate(${tilt}deg)` },
        ], 150, 'cubic-bezier(.7,0,1,.6)');

        // 3) 着弾
        const hit = { x: ac.x + tx + ux * a.height * 0.55, y: ac.y + ty + uy * a.height * 0.55 };
        hitFx(stage, hit, ux, uy, power);
        flashAt(holder, rectOf(tgt), '#fff', 200, 0.95);
        await sleep(80);   // ヒットストップ
        knockback(tgt, ux, uy);
        if (opts.onImpact) opts.onImpact(hit);

        // 4) 戻る
        att.classList.remove('auto-fx-charging');
        await anim(att, [
            { transform: `translate(${tx}px,${ty}px) scale(1.14) rotate(${tilt}deg)` },
            { transform: `translate(${tx * 0.85}px,${ty * 0.85}px) scale(1.08) rotate(${tilt * 0.3}deg)`, offset: 0.2 },
            { transform: 'translate(0,0) scale(1) rotate(0deg)' },
        ], 420, 'cubic-bezier(.2,.8,.2,1)');
    }

    function hitFx(stage, pt, ux, uy, power) {
        const p = Math.min(2, 0.8 + power * 0.15);
        const base = Math.atan2(uy, ux);
        fxAdd(stage, { type: 'burst', x: pt.x, y: pt.y, glow: 60 * p, c: '40,100%,75%', life: 320,
            rays: rays(12, 40 * p, 95 * p) });
        fxAdd(stage, { type: 'ring', x: pt.x, y: pt.y, r0: 8, r1: 70 * p, w: 6, c: '30,100%,70%', life: 380 });
        for (let i = 0; i < 22 * p; i++) {
            const a = base + rand(-1.3, 1.3);
            const v = rand(4, 12) * p;
            fxAdd(stage, { type: 'spark', x: pt.x, y: pt.y, vx: Math.cos(a) * v, vy: Math.sin(a) * v,
                g: 0.3, len: 2.4, size: 3, c: pick(['35,100%,70%', '15,100%,65%', '50,100%,85%']),
                life: rand(300, 600) });
        }
    }

    /** 対象を着弾方向へ少し押し込む。★translate 属性で掛ける(本物の transform を上書きしない) */
    function knockback(el, ux, uy) {
        if (!el) return;
        el.animate([
            { translate: '0px 0px' },
            { translate: `${(ux * 14).toFixed(1)}px ${(uy * 14).toFixed(1)}px` },
            { translate: `${(-ux * 5).toFixed(1)}px ${(-uy * 5).toFixed(1)}px` },
            { translate: '0px 0px' },
        ], { duration: 320, easing: 'ease-out' });
    }

    // =================================================================
    // 7) 数値の反応(デモの feedback.js)
    // =================================================================

    /**
     * ダメージ。★数字・赤い膜・小さな火花。★<b>数値の表示そのものは描き直しが既に持っている</b>。
     * ★★<b>量を見て出すかどうかを決めない</b> —— それは battle.js の対応表の判断である
     * (+0 を出さない、は画面の規則であって演出の規則ではない。判定を2箇所に置かない)。
     */
    function showDamage(stage, holder, el, amount) {
        if (!el) return null;
        const r = rectOf(el);
        flashAt(holder, r, '#ff2a1a', 450, 0.55);
        const c = center(r);
        for (let i = 0; i < 10; i++) {
            const a = rand(0, 6.283);
            const v = rand(2, 6);
            fxAdd(stage, { type: 'spark', x: c.x, y: c.y, vx: Math.cos(a) * v, vy: Math.sin(a) * v,
                g: 0.2, len: 2, size: 2.2, c: pick(['15,100%,65%', '40,100%,75%']), life: rand(220, 420) });
        }
        return popNumber(stage, r, '-' + amount, '');
    }

    function showHeal(stage, holder, el, amount) {
        if (!el) return null;
        const r = rectOf(el);
        flashAt(holder, r, 'hsl(100 90% 80%)', 500, 0.7);
        const c = center(r);
        fxAdd(stage, { type: 'ring', x: c.x, y: c.y, r0: 10, r1: r.width * 1.1, w: 5,
            c: '100,90%,75%', life: 500 });
        for (let i = 0; i < 16; i++) {
            fxAdd(stage, { type: 'dot', add: true, x: c.x + rand(-0.5, 0.5) * r.width,
                y: r.bottom - rand(0, 0.4) * r.height, vx: rand(-0.2, 0.2), vy: rand(-2.6, -1),
                drag: 0.99, size: rand(1.5, 3.2), c: pick(['100,90%,75%', '55,100%,80%']),
                life: rand(500, 800), delay: rand(0, 200) });
        }
        return popNumber(stage, r, '+' + amount, 'heal');
    }

    // =================================================================
    // 8) 破壊(デモの death.js)。★el は層の中の複製である(本物は描き直しで既に居ない)
    // =================================================================

    /**
     * 着弾点 P から放射状に亀裂を入れ、内側・外側の二重リングで破片ポリゴンを作る。
     * 座標はカードのボーダーボックス左上を原点とするピクセル値。
     */
    function crackGeometry(w, h, P, n) {
        const step = (2 * Math.PI) / n;
        const a0 = rand(0, step);
        const angs = Array.from({ length: n }, (_, i) => a0 + i * step + rand(-0.25, 0.25) * step);
        const ref = angs[0];
        const rel = (a) => (((a - ref) % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
        const rays_ = angs.map((a) => {
            const dx = Math.cos(a);
            const dy = Math.sin(a);
            const t = Math.min(dx > 0 ? (w - P.x) / dx : dx < 0 ? -P.x / dx : Infinity,
                dy > 0 ? (h - P.y) / dy : dy < 0 ? -P.y / dy : Infinity);
            const at = (f, j) => [P.x + dx * t * f - dy * j, P.y + dy * t * f + dx * j];
            return [[P.x, P.y], at(rand(0.28, 0.42), rand(-0.04, 0.04) * w),
                at(rand(0.6, 0.78), rand(-0.05, 0.05) * w), [P.x + dx * t, P.y + dy * t]];
        });
        const corners = [[0, 0], [w, 0], [w, h], [0, h]]
            .map((c) => ({ c: c, r: rel(Math.atan2(c[1] - P.y, c[0] - P.x)) }));
        const polys = [];
        for (let i = 0; i < n; i++) {
            const A = rays_[i];
            const B = rays_[(i + 1) % n];
            const lo = angs[i] - ref;
            const hi = i === n - 1 ? 2 * Math.PI : angs[i + 1] - ref;
            const cs = corners.filter((o) => o.r > lo && o.r < hi).sort((x, y) => x.r - y.r).map((o) => o.c);
            polys.push([A[0], A[1], B[1]]);
            polys.push([A[1], A[2], A[3], ...cs, B[3], B[2], B[1]]);
        }
        const lines = [...rays_, ...rays_.map((r, i) => [r[1], rays_[(i + 1) % n][1]])];
        return { polys: polys, lines: lines, rayCount: n };
    }

    function crackSvg(geo, w, h, hue) {
        const NS = 'http://www.w3.org/2000/svg';
        const svg = document.createElementNS(NS, 'svg');
        svg.setAttribute('class', 'auto-fx-cracks');
        svg.setAttribute('viewBox', `0 0 ${w} ${h}`);
        Object.assign(svg.style, { left: '0px', top: '0px', width: w + 'px', height: h + 'px' });
        geo.lines.forEach((pts, i) => {
            for (const [stroke, width] of [[`hsla(${hue},100%,62%,.6)`, 4.5], [`hsl(${hue},100%,94%)`, 1.4]]) {
                const pl = document.createElementNS(NS, 'polyline');
                pl.setAttribute('points', pts.map((p) => p.join(',')).join(' '));
                Object.assign(pl.style, { fill: 'none', stroke: stroke, strokeWidth: width,
                    strokeLinecap: 'round', strokeLinejoin: 'round' });
                pl.dataset.i = i;
                svg.appendChild(pl);
            }
        });
        return svg;
    }

    /**
     * ひびが走る → 割れ目から光が漏れて震える → 複製を破片に切り分けて飛ばす → 余韻。
     * ★<b>破片と焦げ跡は余韻</b>であり、holder の外(層の直下)に置いて自分で消える。
     * @param o.hue  光の色相(文明の色から battle.js が決める)
     */
    async function playDeath(stage, holder, el, o) {
        const opts = o || {};
        const hue = opts.hue === undefined ? 20 : opts.hue;
        const col = `${hue},100%,70%`;
        const r = rectOf(el);
        const w = r.width;
        const h = r.height;
        if (!w || !h) return;
        const P = opts.hit
            ? { x: clamp(opts.hit.x - r.left, w * 0.22, w * 0.78), y: clamp(opts.hit.y - r.top, h * 0.2, h * 0.8) }
            : { x: w * rand(0.4, 0.6), y: h * rand(0.35, 0.55) };
        const px = r.left + P.x;
        const py = r.top + P.y;

        // 1) ひびと、割れ目から漏れる光
        const geo = crackGeometry(w, h, P, 8);
        el.appendChild(crackSvg(geo, w, h, hue));
        el.querySelectorAll('.auto-fx-cracks polyline').forEach((pl) => {
            const L = pl.getTotalLength();
            const ring = +pl.dataset.i >= geo.rayCount;
            pl.style.strokeDasharray = L;
            pl.animate([{ strokeDashoffset: L }, { strokeDashoffset: 0 }], {
                duration: ring ? 140 : 240, delay: ring ? 200 + rand(0, 80) : rand(0, 70),
                easing: 'cubic-bezier(.3,.7,.4,1)', fill: 'both',
            });
        });
        const glow = document.createElement('div');
        glow.className = 'auto-fx-flashov auto-fx-dglow';
        glow.style.background = `radial-gradient(circle at ${P.x}px ${P.y}px, hsla(${hue},100%,80%,.95), `
            + `hsla(${hue},100%,55%,.35) 45%, hsla(${hue},100%,50%,0) 80%)`;
        glow.style.mixBlendMode = 'screen';
        el.appendChild(glow);
        glow.animate([{ opacity: 0 }, { opacity: 0.25, offset: 0.3 }, { opacity: 1 }],
            { duration: 520, easing: 'ease-in', fill: 'forwards' });
        fxAdd(stage, { type: 'burst', x: px, y: py, glow: w * 0.35, c: col, life: 260,
            rays: rays(8, 0.3 * w, 0.6 * w) });
        for (let i = 0; i < 18; i++) {
            const a = rand(0, 6.283);
            const d = rand(0.7, 1.3) * w;
            fxAdd(stage, { type: 'gather', sx: px + Math.cos(a) * d, sy: py + Math.sin(a) * d, tx: px, ty: py,
                size: rand(1.5, 3), c: col, life: rand(300, 420), delay: rand(80, 200) });
        }
        const tremble = [];
        for (let i = 0; i <= 10; i++) {
            const m = (i / 10) * 4;
            tremble.push({ transform: `translate(${rand(-m, m)}px,${rand(-m, m)}px) scale(${1 + (0.06 * i) / 10})` });
        }
        await anim(el, tremble, 520, 'linear');
        if (!el.isConnected) return;

        // 2) 砕け散る: 複製を破片の形に切り抜いた複製を飛ばす(★余韻。holder の外に置く)
        const shards = document.createElement('div');
        shards.className = 'auto-fx-shatter';
        Object.assign(shards.style, { left: r.left + 'px', top: r.top + 'px', width: w + 'px', height: h + 'px' });
        const base = el.cloneNode(true);
        base.style.cssText = `position:absolute;left:0;top:0;margin:0;width:${w}px;height:${h}px;box-shadow:none`;
        const baseGlow = base.querySelector('.auto-fx-dglow');
        if (baseGlow) baseGlow.style.opacity = 0.7;
        let longest = 0;
        for (const poly of geo.polys) {
            const sh = base.cloneNode(true);
            const cx = poly.reduce((s, p) => s + p[0], 0) / poly.length;
            const cy = poly.reduce((s, p) => s + p[1], 0) / poly.length;
            sh.style.clipPath = `polygon(${poly.map((p) => `${p[0].toFixed(1)}px ${p[1].toFixed(1)}px`).join(',')})`;
            sh.style.transformOrigin = `${cx}px ${cy}px`;
            shards.appendChild(sh);
            let ddx = cx - P.x;
            let ddy = cy - P.y;
            const d = Math.hypot(ddx, ddy) || 1;
            ddx /= d; ddy /= d;
            const near = 1.35 - Math.min(1, d / w);   // 着弾点に近い破片ほど強く飛ぶ
            const spd = w * 1.05 * rand(0.7, 1.25) * near;
            const up = -h * rand(0.15, 0.45);
            const G = h * rand(1.3, 1.9);
            const rx = rand(-420, 420);
            const rz = rand(-260, 260);
            const T = rand(950, 1300);
            const kf = [];
            for (let i = 0; i <= 10; i++) {
                const k = i / 10;
                const e = 1 - (1 - k) ** 2;
                // ★3D 回転は GPU のない端末で重いため、scaleX の反転で裏返りを擬似的に表す(デモのまま)
                const sc = 1.06 - 0.3 * k;
                const flipX = Math.cos((rx * e * Math.PI) / 180);
                kf.push({
                    transform: `translate(${ddx * spd * e}px,${ddy * spd * e + up * e + G * k * k}px) `
                        + `rotate(${rz * e}deg) scale(${(flipX * sc).toFixed(3)},${sc.toFixed(3)})`,
                    opacity: k < 0.55 ? 1 : 1 - (k - 0.55) / 0.45,
                });
            }
            sh.animate(kf, { duration: T, easing: 'linear', fill: 'forwards' });
            longest = Math.max(longest, T);
        }
        stage.layer.appendChild(shards);
        el.style.opacity = '0';
        clearAnims(el);
        setTimeout(() => shards.remove(), longest + 50);

        // 3) 余韻
        screenFlash(stage, 0.3, 220);
        fxAdd(stage, { type: 'burst', x: px, y: py, glow: w * 0.9, c: col, life: 380,
            rays: rays(16, 0.8 * w, 1.5 * w) });
        fxAdd(stage, { type: 'ring', x: px, y: py, r0: 6, r1: w * 1.4, w: 7, c: '0,0%,95%', life: 420 });
        fxAdd(stage, { type: 'ring', x: px, y: py, r0: 6, r1: w * 2, w: 4, c: col, life: 650, delay: 70 });
        for (let i = 0; i < 26; i++) {
            const a = rand(0, 6.283);
            const v = rand(3, 10);
            fxAdd(stage, { type: 'spark', x: px, y: py, vx: Math.cos(a) * v, vy: Math.sin(a) * v, g: 0.22,
                len: 2.2, size: 2.5, c: pick([col, '45,100%,80%', '0,0%,100%']), life: rand(350, 700) });
        }
        for (let i = 0; i < 22; i++) {
            fxAdd(stage, { type: 'dot', add: true, x: r.left + rand(0.1, 0.9) * w, y: r.top + rand(0.2, 0.9) * h,
                vx: rand(-0.6, 0.6), vy: rand(-2.6, -0.8), drag: 0.985, size: rand(1.2, 2.6),
                c: pick([col, '38,100%,70%']), life: rand(900, 1500), delay: rand(0, 250) });
        }
        for (let i = 0; i < 9; i++) {
            fxAdd(stage, { type: 'dot', x: px + rand(-0.4, 0.4) * w, y: py + rand(-0.3, 0.3) * h,
                vx: rand(-0.8, 0.8), vy: rand(-1.4, -0.4), drag: 0.97, size: rand(6, 11), grow: 1.6, a: 0.35,
                c: '260,8%,35%', life: rand(800, 1200) });
        }
        fxAdd(stage, { type: 'wisp', x0: r.left + w / 2, y0: r.top + h / 2, rise: h * 1.2, sway: w * 0.18,
            size: w * 0.32, c: col, life: 1400, delay: 160 });
        const scorch = document.createElement('div');
        scorch.className = 'auto-fx-scorch';
        Object.assign(scorch.style, { left: r.left - w * 0.25 + 'px', top: r.top + h * 0.2 + 'px',
            width: w * 1.5 + 'px', height: h * 0.75 + 'px' });
        stage.layer.appendChild(scorch);
        scorch.animate([
            { opacity: 0, transform: 'scale(.5)' },
            { opacity: 1, transform: 'scale(1)', offset: 0.12 },
            { opacity: 0, transform: 'scale(1.1)' },
        ], { duration: 1800, easing: 'ease-out' }).finished.catch(() => null).then(() => scorch.remove());
    }

    // =================================================================
    // 9) ★消滅(裁定372)。★デモに無い演出である —— デモの語彙(魔法陣・光の膜・昇る光)で組んだ
    // =================================================================

    /**
     * 足元に魔法陣 → 光の膜に包まれる → 縦に伸びながら光にほどけて昇る。
     * ★<b>砕けない</b>(破壊と区別する)。★音も揺れも出さない —— 静かに居なくなる。
     */
    async function playBanish(stage, el, o) {
        const opts = o || {};
        const hue = opts.hue === undefined ? 270 : opts.hue;
        const col = `${hue},90%,78%`;
        const r = rectOf(el);
        if (!r.width || !r.height) return;
        const c = center(r);
        fxAdd(stage, { type: 'rune', x: c.x, y: r.bottom - r.height * 0.05, r: r.width * 0.85, flat: 0.35,
            n: 5, c: col, life: MS.BANISH });
        const veil = document.createElement('div');
        veil.className = 'auto-fx-flashov';
        veil.style.background = `radial-gradient(circle, #fff, hsl(${hue} 90% 78%))`;
        el.appendChild(veil);
        veil.animate([{ opacity: 0 }, { opacity: 0.9 }], { duration: 300, easing: 'ease-in', fill: 'forwards' });
        for (let i = 0; i < 18; i++) {
            fxAdd(stage, { type: 'orbit', x: c.x, y: r.bottom - r.height * 0.1, a0: (i / 18) * 6.283, w: 0.008,
                r0: r.width * 0.7, r1: r.width * 0.3, flat: 0.38, rise: r.height * 1.1, size: rand(4, 7),
                c: col, life: 800, delay: i * 14 });
        }
        await anim(el, [{ transform: 'none' }, { transform: 'translateY(-4px) scale(1.04)' }], 300, 'ease-out');
        if (!el.isConnected) return;
        for (let i = 0; i < 24; i++) {
            fxAdd(stage, { type: 'dot', add: true, x: r.left + rand(0.1, 0.9) * r.width,
                y: r.top + rand(0.1, 0.9) * r.height, vx: rand(-0.3, 0.3), vy: rand(-3.2, -1.2), drag: 0.99,
                size: rand(1.5, 3.4), c: pick([col, '0,0%,100%']), life: rand(600, 1000), delay: rand(0, 200) });
        }
        fxAdd(stage, { type: 'wisp', x0: c.x, y0: c.y, rise: r.height * 1.1, sway: r.width * 0.12,
            size: r.width * 0.3, c: col, life: 1100 });
        await anim(el, [
            { transform: 'translateY(-4px) scale(1.04)', opacity: 1 },
            { transform: `translateY(${-r.height * 0.45}px) scale(0.55, 1.35)`, opacity: 0 },
        ], 400, 'cubic-bezier(.5,0,.9,.5)');
    }

    // =================================================================
    // 10) 召喚の着地(デモの summon.js)。★el は層の中の複製(本物の真上に置いたもの)
    // =================================================================

    /**
     * 浮き上がる → 足元に魔法陣 → 溜め → 叩きつけ → 衝撃波。
     * ★<b>飛び立つ元は描かない</b> —— 出どころはサーバが運ばない(85a 2-5)。
     * 手札から出たなら 80 の差分の飛行が先に語っている(battle.js が順に並べる)。
     */
    async function playSummon(stage, el, o) {
        const opts = o || {};
        const col = opts.color || '160,70%,70%';
        const to = rectOf(el);
        const c = center(to);
        const hy = (opts.side === 'opponent' ? 1 : -1) * to.height * 0.55;

        // 1) 浮き上がる
        fxAdd(stage, { type: 'rune', x: c.x, y: c.y + to.height * 0.1, r: to.width * 0.95, flat: 0.42,
            n: 6, c: col, life: 1250 });
        await anim(el, [
            { transform: 'translate(0px,0px) scale(1)', opacity: 0.2 },
            { transform: `translate(0px,${hy}px) scale(1.3)`, opacity: 1 },
        ], 260, 'cubic-bezier(.2,.9,.25,1)');
        if (!el.isConnected) return;
        // 2) 溜め
        await anim(el, [
            { transform: `translate(0px,${hy}px) scale(1.3)` },
            { transform: `translate(0px,${hy * 1.15}px) scale(1.4)` },
        ], 200, 'ease-out');
        if (!el.isConnected) return;
        // 3) 叩きつける
        await anim(el, [
            { transform: `translate(0px,${hy * 1.15}px) scale(1.4)` },
            { transform: 'translate(0px,0px) scale(1)' },
        ], 170, 'cubic-bezier(.55,0,1,.45)');
        clearAnims(el);
        landImpact(stage, to, 1, col);
        flashOn(el, '#fff', 380);
        await anim(el, [{ transform: 'scale(1.1,.88)' }, { transform: 'scale(.97,1.04)' }, { transform: 'scale(1)' }],
            260, 'ease-out');
    }

    /** 着地の衝撃: 地面の衝撃波・砂煙・火花(★揺れは出さない。揺らすのは大きいダメージだけ・裁定375) */
    function landImpact(stage, r, power, col) {
        const c = center(r);
        const by = r.bottom - r.height * 0.05;
        fxAdd(stage, { type: 'ring', x: c.x, y: by, r0: r.width * 0.3, r1: r.width * 1.3 * power, flat: 0.35,
            w: 7, c: col, life: 520 });
        for (let i = 0; i < 16 * power; i++) {
            const x = c.x + rand(-0.5, 0.5) * r.width;
            const s = Math.sign(x - c.x) || 1;
            fxAdd(stage, { type: 'dot', x: x, y: by + rand(-4, 4), vx: s * rand(1.5, 5) * power, vy: rand(-2, -0.3),
                drag: 0.93, size: rand(3, 7), grow: 2.2, a: 0.5, c: '35,15%,72%', life: rand(500, 900) });
        }
        for (let i = 0; i < 10 * power; i++) {
            const a = rand(Math.PI * 1.05, Math.PI * 1.95);
            fxAdd(stage, { type: 'spark', x: c.x + rand(-0.4, 0.4) * r.width, y: by, vx: Math.cos(a) * rand(3, 9),
                vy: Math.sin(a) * rand(3, 9), g: 0.25, len: 2.2, size: 2.5, c: col, life: rand(350, 650) });
        }
    }

    // =================================================================
    // 11) 呪文(デモの spells.js)
    // =================================================================

    /**
     * 詠唱(全呪文共通の前半)。カードを詠唱位置へ → 魔法陣と光の収束 → カードが光にほどける。
     *
     * ★★<b>効果ごとの演出(火球・稲妻…)は持ち込まない。</b>デモは呪文の定義に演出名を持っていたが、
     * QTE の235枚は効果を1つも名乗らない —— 名乗らせると<b>カードの正が2つになる</b>。
     * ★後半は {@link playBolt}(詠唱位置から対象へ弾を飛ばす)1つで語る。
     *
     * @param face  詠唱するカードの面(battle.js の cardFace が作ったもの)
     * @param o.side  'you' なら画面下寄り、'opponent' なら上寄りに出る
     * @param o.color 文明の色("色相,彩度%,明度%")
     * @return 詠唱位置(効果の発射点・画面座標)
     */
    async function playCastIntro(stage, holder, face, o) {
        const opts = o || {};
        const col = opts.color || '270,100%,68%';
        const probe = document.createElement('div');
        probe.className = 'auto-fx-cast';
        probe.appendChild(face);
        holder.appendChild(probe);
        const w = probe.offsetWidth;
        const h = probe.offsetHeight;
        const cx = viewW() / 2;
        const cy = viewH() * (opts.side === 'opponent' ? 0.36 : 0.56);
        Object.assign(probe.style, { left: cx - w / 2 + 'px', top: cy - h / 2 + 'px' });
        await anim(probe, [
            { transform: `translate(0px,${(opts.side === 'opponent' ? -1 : 1) * h * 1.2}px) scale(.6)`, opacity: 0 },
            { transform: 'translate(0,0) scale(1.5)', opacity: 1 },
        ], 320, 'cubic-bezier(.2,.9,.25,1)');
        if (!probe.isConnected) return { x: cx, y: cy };

        // 詠唱: 背後に正面向きの魔法陣、光の粒がらせんに集まる
        fxAdd(stage, { type: 'rune', x: cx, y: cy, r: w * 1.25, flat: 1, n: 6, c: col, life: 1300 });
        fxAdd(stage, { type: 'rune', x: cx, y: cy, r: w * 0.8, flat: 1, n: 3, c: '0,0%,90%', life: 1100, delay: 120 });
        for (let i = 0; i < 26; i++) {
            fxAdd(stage, { type: 'orbit', x: cx, y: cy, a0: rand(0, 6.283), w: rand(0.006, 0.012) * (i % 2 ? 1 : -1),
                r0: w * rand(1.2, 1.7), r1: w * 0.1, flat: 1, rise: 0, size: rand(5, 9), c: col,
                life: rand(550, 800), delay: rand(0, 250) });
        }
        const veil = document.createElement('div');
        veil.className = 'auto-fx-flashov';
        veil.style.background = `radial-gradient(circle, #fff, hsla(${col},1))`;
        probe.appendChild(veil);
        veil.animate([{ opacity: 0 }, { opacity: 1 }], { duration: 440, easing: 'ease-in', fill: 'forwards' });
        await anim(probe, [
            { transform: 'scale(1.5)' }, { transform: 'translateY(-6px) scale(1.54)' }, { transform: 'scale(1.5)' },
        ], 440, 'ease-in-out');
        if (!probe.isConnected) return { x: cx, y: cy };

        // カードが光にほどける
        anim(probe, [{ transform: 'scale(1.5)', opacity: 1 }, { transform: 'scale(.2)', opacity: 0 }],
            220, 'cubic-bezier(.6,0,1,1)');
        fxAdd(stage, { type: 'burst', x: cx, y: cy, glow: w * 0.9, c: col, life: 300, rays: rays(12, 0.6 * w, 1.1 * w) });
        fxAdd(stage, { type: 'ring', x: cx, y: cy, r0: 10, r1: w * 1.6, w: 5, c: col, life: 420 });
        await sleep(140);
        return { x: cx, y: cy };
    }

    /**
     * 詠唱位置から対象へ弾を飛ばす(デモの fireball の前半)。着弾の瞬間に onHit を呼ぶ。
     * ★回復なら光の柱を降らせる(デモの heal)。
     */
    async function playBolt(stage, holder, from, tgt, o) {
        const opts = o || {};
        const col = opts.color || '28,100%,60%';
        const r = rectOf(tgt);
        const t = center(r);
        if (opts.heal) {
            fxAdd(stage, { type: 'beam', x: t.x, y: r.bottom, w: r.width * 0.8, c: '100,90%,70%', life: MS.BOLT + 300 });
            fxAdd(stage, { type: 'rune', x: t.x, y: r.bottom - r.height * 0.05, r: r.width * 0.9, flat: 0.35, n: 8,
                c: '100,90%,70%', life: MS.BOLT + 400 });
            await sleep(MS.BOLT);
            if (opts.onHit) opts.onHit(t);
            return;
        }
        fxAdd(stage, { type: 'proj', sx: from.x, sy: from.y, tx: t.x, ty: t.y,
            cx: (from.x + t.x) / 2 + rand(-80, 80), cy: Math.min(from.y, t.y) - 120, size: 22, c: col,
            life: MS.BOLT });
        await sleep(MS.BOLT);
        fxAdd(stage, { type: 'burst', x: t.x, y: t.y, glow: 90, c: col, life: 380, rays: rays(14, 60, 120) });
        fxAdd(stage, { type: 'ring', x: t.x, y: t.y, r0: 10, r1: 120, w: 7, c: col, life: 460 });
        for (let i = 0; i < 28; i++) {
            const a = rand(0, 6.283);
            const v = rand(3, 10);
            fxAdd(stage, { type: 'dot', add: true, x: t.x, y: t.y, vx: Math.cos(a) * v, vy: Math.sin(a) * v, drag: 0.9,
                size: rand(3, 8), c: pick([col, '8,100%,55%', '45,100%,70%']), life: rand(300, 600) });
        }
        flashAt(holder, r, '#fff', 250, 0.95);
        if (opts.onHit) opts.onHit(t);
    }

    // =================================================================
    // 12) 公開窓口
    // =================================================================

    window.QteFx = Object.freeze({
        MS: MS,
        mount: mount,
        run: run,
        cloneAt: cloneAt,
        rectOf: rectOf,
        playAttack: playAttack,
        playDeath: playDeath,
        playBanish: playBanish,
        playSummon: playSummon,
        playCastIntro: playCastIntro,
        playBolt: playBolt,
        showDamage: showDamage,
        showHeal: showHeal,
        shake: shake,
        screenFlash: screenFlash,
        flashOn: flashOn,
        flashAt: flashAt,
        banner: banner,
        clearBanner: clearBanner,
    });
})();
