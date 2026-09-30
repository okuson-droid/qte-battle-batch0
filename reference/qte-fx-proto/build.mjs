import { build } from 'esbuild';
import { readFile, writeFile, mkdir } from 'node:fs/promises';
const js = await build({ entryPoints: ['src/main.js'], bundle: true, format: 'iife', minify: false, write: false, charset: 'utf8' });
const css = await readFile('src/style.css', 'utf8');
const body = `<title>QTE 演出プロトタイプ</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Sans+JP:wght@400;700;800&family=Noto+Serif+JP:wght@900&display=swap">
<style>
${css}</style>
<div id="game"></div>
<script>
${js.outputFiles[0].text}</script>
`;
await mkdir('dist', { recursive: true });
await writeFile('dist/qte-fx-proto.html', body);
await writeFile('dist/local.html', `<!doctype html><html lang="ja"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n${body}</head></html>`);
console.log('built', body.length);
