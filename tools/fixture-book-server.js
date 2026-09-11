// Fixture book server for device verification of the catalogue crawl.
// Mimics a paginated Chinese novel site: /book/ (catalogue page 1) -> /book/2 ... /book/12,
// each catalogue page listing chapter links, plus split chapters with a "下一页" continuation.
const http = require('node:http');
const PORT = Number(process.argv[2] || 8765);
const PAGE_COUNT = 12;
const CHAPTERS_PER_PAGE = 20;

function catalogPage(page) {
  const from = (page - 1) * CHAPTERS_PER_PAGE + 1;
  const to = page * CHAPTERS_PER_PAGE;
  const links = [];
  for (let n = from; n <= to; n += 1) {
    links.push(`<li><a href="/book/${n}.html">第${n}章 测试章节标题</a></li>`);
  }
  const nav = [];
  if (page > 1) nav.push(`<a href="/book/${page === 2 ? '' : page - 1}">上一页</a>`);
  if (page < PAGE_COUNT) nav.push(`<a href="/book/${page + 1}">下一页</a>`);
  return `<!doctype html><html><head><meta charset="utf-8"><title>测试小说 目录</title></head><body>
<div class="bookinfo"><h1>测试小说</h1><p>作者：测试</p></div>
<div id="list" class="listmain"><dl>${links.join('')}</dl></div>
<div class="listpage">${nav.join(' ')}<span>共 ${PAGE_COUNT} 页</span></div>
</body></html>`;
}

function chapterPage(n, part) {
  const totalParts = 2;
  const paragraphs = [];
  for (let i = 0; i < 6; i += 1) {
    paragraphs.push(`<p>第${n}章第${part}段第${i + 1}句，用于验证目录按需抓取与正文合并。</p>`);
  }
  const nextPart = part < totalParts
    ? `<a href="/book/${n}_${part + 1}.html">下一页</a>`
    : `<a href="/book/${n + 1}.html">下一章</a>`;
  const prevPart = part > 1
    ? `<a href="/book/${n}_${part - 1}.html">上一页</a>`
    : `<a href="/book/${n - 1}.html">上一章</a>`;
  return `<!doctype html><html><head><meta charset="utf-8"><title>第${n}章 测试章节标题</title></head><body>
<div class="bookname"><h1>第${n}章 测试章节标题</h1></div>
<div id="content" class="content">${paragraphs.join('')}</div>
<div class="bottem1">${prevPart} <a href="/book/">目录</a> ${nextPart}</div>
</body></html>`;
}

const server = http.createServer((req, res) => {
  const path = decodeURIComponent((req.url || '/').split('?')[0]);
  const send = (body) => {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(body);
  };
  let m = path.match(/^\/book\/(\d+)_(\d+)\.html$/);
  if (m) return send(chapterPage(Number(m[1]), Number(m[2])));
  m = path.match(/^\/book\/(\d+)\.html$/);
  if (m) return send(chapterPage(Number(m[1]), 1));
  m = path.match(/^\/book\/?(\d*)\/?$/);
  if (m) {
    const page = m[1] === '' ? 1 : Number(m[1]);
    if (page >= 1 && page <= PAGE_COUNT) return send(catalogPage(page));
  }
  res.writeHead(404, { 'Content-Type': 'text/plain' });
  res.end('not found');
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`fixture book server on http://0.0.0.0:${PORT}/book/`);
});
