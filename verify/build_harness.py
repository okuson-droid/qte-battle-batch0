#!/usr/bin/env python3
"""Batch 20b 検証用ハーネスの生成。

Thymeleaf テンプレート `manual-battle.html` を、実ファイルの `battle.css` /
`manual-battle.js` をそのまま読み込む素の HTML へ変換する。

★合成 DragEvent は使わない。ここで作るのは「実ファイルを実際のブラウザに載せた盤面」であり、
検証そのものは Playwright の page.mouse による実マウス操作で行う(19b hotfix2・20a 3-1/3-2)。

外部 CDN(Bootstrap / StompJs)はサンドボックスのネットワーク制限で読み込めないため、
- Bootstrap: 検証に必要な `.d-none` だけを最小限のスタイルで代替する
- StompJs: 送信内容を window.__sent へ捕捉するスタブに差し替える
"""
import re
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEMPLATE = ROOT / "src/main/resources/templates/manual-battle.html"
OUT = pathlib.Path(__file__).resolve().parent / "harness.html"

html = TEMPLATE.read_text(encoding="utf-8")

# CDN を落とす(読み込めないうえ、待ち時間が検証を不安定にする)
html = html.replace(
    '<link href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css" rel="stylesheet">',
    "",
)
html = html.replace(
    '<script src="https://cdn.jsdelivr.net/npm/@stomp/stompjs@7.0.0/bundles/stomp.umd.min.js"></script>',
    "",
)

# 実ファイルを相対パスで読ませる
html = html.replace(
    '<link th:href="@{/css/battle.css(v=16)}" rel="stylesheet">',
    '<link href="/css/battle.css" rel="stylesheet">',
)
html = html.replace(
    '<script th:src="@{/js/manual-battle.js(v=8)}"></script>',
    '<script src="/js/manual-battle.js"></script>',
)

# Thymeleaf 属性を落とす(値は静的なもので置き換える)
html = html.replace('th:text="${roomId}">------', ">TESTRM")
html = re.sub(r'\s+th:href="[^"]*"', ' href="#"', html)
html = html.replace("/*[[${roomId}]]*/ ''", "'TESTRM'")
html = html.replace("/*[[${defaultLabels}]]*/ []", "['凍結', '守護']")

# Bootstrap の代替(検証で効いている必要があるのは非表示制御だけ)+ STOMP スタブ
stub = """
<style>
  .d-none { display: none !important; }
  body { font-family: sans-serif; }
  .info-modal { position: fixed; inset: 0; background: rgba(0,0,0,.6); z-index: 2000;
                display: flex; align-items: center; justify-content: center; }
  .info-modal-body { background: #222; padding: 16px; border-radius: 6px; }
  /* ★Bootstrap の代替。ここに漏れがあると「ハーネスでだけ壊れる」ため、
     テンプレートで使っているユーティリティは必ず足すこと(20cで flex-column の
     欠落によりログ幅が16pxになり、実態とズレた検証をしかけた)。 */
  .container-fluid { width: 100%; box-sizing: border-box; }
  .d-flex { display: flex; } .flex-wrap { flex-wrap: wrap; }
  .flex-column { flex-direction: column; }
  .ms-auto { margin-left: auto; } .gap-1 { gap: 4px; } .gap-2 { gap: 8px; }
  .align-items-center { align-items: center; } .align-items-end { align-items: flex-end; }
  .justify-content-between { justify-content: space-between; }
  .justify-content-end { justify-content: flex-end; }
  .justify-content-center { justify-content: center; }
  .mb-0 { margin-bottom: 0; } .mb-1 { margin-bottom: 4px; } .mb-2 { margin-bottom: 8px; }
  .mt-1 { margin-top: 4px; } .mt-2 { margin-top: 8px; }
  .p-2 { padding: 8px; } .py-1 { padding-top: 4px; padding-bottom: 4px; }
  .py-2 { padding: 8px 0; } .px-2 { padding-left: 8px; padding-right: 8px; }
  .w-100 { width: 100%; } .small { font-size: 0.875em; }
  .btn-group { display: inline-flex; }
  .form-control { width: 100%; box-sizing: border-box; }
</style>
<script>
  window.__sent = [];
  window.StompJs = {
    Client: class {
      constructor(options) { this.options = options; }
      activate() { /* 検証では接続しない。renderAll を直接呼ぶ */ }
      deactivate() {}
      subscribe() {}
      publish(message) {
        window.__sent.push({
          destination: message.destination,
          body: JSON.parse(message.body),
        });
      }
    },
  };
  // ★入室ダイアログ(prompt)を出さないため、occupant を先に保存しておく
  localStorage.setItem('qte-manual-occupant-TESTRM',
      JSON.stringify({ occupantId: 'occ-test', displayName: 'テスト' }));
</script>
"""
html = html.replace("</head>", stub + "</head>")

OUT.write_text(html, encoding="utf-8")
print(f"wrote {OUT} ({len(html)} bytes)")
