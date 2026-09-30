// デモを 1 つの HTML ファイルにまとめる（ブラウザでファイルを直接開いて動かせるようにするため）。
// ES モジュールは file:// では読み込めないので、配布・確認用に dist/ へ書き出す。
import { build } from 'esbuild';
import { readFile, writeFile, mkdir } from 'node:fs/promises';

const js = await build({ entryPoints: ['src/main.js'], bundle: true, format: 'iife', minify: false, write: false });
const css = (await readFile('src/style.css', 'utf8')) + '\n' + (await readFile('demo.css', 'utf8'));
let html = await readFile('index.html', 'utf8');
html = html
  .replace(/<link rel="stylesheet"[^>]*>\n?/g, '')
  .replace('</head>', `<style>\n${css}</style>\n</head>`)
  .replace('<script type="module" src="src/main.js"></script>', () => `<script>\n${js.outputFiles[0].text}</script>`);
await mkdir('dist', { recursive: true });
await writeFile('dist/card_battle_demo.html', html);
console.log('dist/card_battle_demo.html を書き出しました');
