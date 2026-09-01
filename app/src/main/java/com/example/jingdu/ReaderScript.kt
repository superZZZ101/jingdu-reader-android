package com.example.jingdu

object ReaderScript {
    val extract = """
        (() => {
          const NOISE = 'script,style,noscript,template,iframe,canvas,svg,nav,aside,header,footer,form,button,input,textarea,select,option,[role="navigation"],[role="complementary"],[aria-hidden="true"],[hidden],.ad,.ads,.advert,.advertisement,.adsbygoogle,.ad-container,[class*="ad-"],[class*="-ad"],[id*="ad-"],[id*="-ad"],.popup,.modal,.overlay,.recommend,.recommendation,.related,.share,.social,.comment,.comments,.toolbar,.pagination,.chapter-nav,.breadcrumb,.notice,.copyright';
          const CATALOG_CONTAINERS = '#list,#catalog,#chapter-list,#chapterList,#目录,.catalog,.catalog-list,.chapter-list,.chapterList,.chapter-list-box,.book-list,.book-chapter-list,.listmain,.volume-list,.directory,[class*="chapter-list"],[id*="chapter-list"]';
          const PAGE_CONTAINERS = '.pagination,.pager,.pages,.page,.page-list,.pageList,[class*="pagination"],[class*="pager"],[class*="page-list"],[id*="pagination"],[id*="pager"],[id*="page-list"]';
          const CATALOG_WORD = /(目录|章节目录|目录页|书目|catalog|contents?)/i;
          const CATALOG_CONTAINER_WORD = /(catalog|chapter[-_]?list|directory|listmain|目录|章节)/i;
          const CHAPTER_WORD = /(第.{1,24}[章回节卷集篇]|序章|楔子|番外|终章|尾声|chapter\s*[0-9一二三四五六七八九十]+)/i;
          const PAGE_WORD = /^(上一页|下一页|上页|下页|前页|后页|第一页|最后一页|第?\s*\d+\s*页|page\s*\d+|\d+\s*[-~～—]\s*\d+\s*(?:章|回|节)?)$/i;
          const BLOCKS = new Set(['ADDRESS','ARTICLE','BLOCKQUOTE','DIV','DL','DT','DD','FIGCAPTION','FIGURE','H1','H2','H3','H4','H5','H6','HEADER','HR','LI','MAIN','OL','P','PRE','SECTION','TABLE','TR','UL']);

          function clean(value) { return String(value || '').replace(/\s+/g, ' ').trim(); }
          function visible(element) { return clean(element.innerText || element.textContent); }
          function hidden(element) {
            if (element.hidden || element.getAttribute('aria-hidden') === 'true') return true;
            const style = element.getAttribute('style') || '';
            if (/display\s*:\s*none|visibility\s*:\s*hidden|opacity\s*:\s*0(?:[;}]|$)/i.test(style)) return true;
            try {
              const computed = getComputedStyle(element);
              return computed.display === 'none' || computed.visibility === 'hidden' || computed.opacity === '0';
            } catch (_) { return false; }
          }
          function hiddenTree(element) {
            let current = element;
            let depth = 0;
            while (current && depth < 12) {
              if (hidden(current)) return true;
              current = current.parentElement;
              depth += 1;
            }
            return false;
          }
          function urlFor(anchor) {
            try {
              const url = new URL(anchor.href, location.href);
              if (!/^https?:$/.test(url.protocol) || url.origin !== location.origin || url.href === location.href) return null;
              return url.href;
            } catch (_) { return null; }
          }
          function labelFor(anchor) { return clean(anchor.textContent || anchor.getAttribute('aria-label') || anchor.title); }
          function pageNumber(label) { return /^(?:\d{1,4}|首页|尾页)$/.test(label); }

          function collectCatalogItems(container) {
            const knownContainer = container !== document.body;
            const seen = new Set();
            const items = [];
            for (const anchor of container.querySelectorAll('a[href]')) {
              if (anchor.closest('#rm-root') || hiddenTree(anchor)) continue;
              const href = urlFor(anchor);
              const label = labelFor(anchor);
              const inPageContainer = Boolean(anchor.closest(PAGE_CONTAINERS));
              if (!href || label.length < 2 || label.length > 140 || PAGE_WORD.test(label) || (inPageContainer && pageNumber(label))) continue;
              if (!knownContainer && !CHAPTER_WORD.test(label + ' ' + anchor.id + ' ' + anchor.className)) continue;
              if (seen.has(href)) continue;
              seen.add(href);
              items.push({ label, href });
            }
            return items;
          }

          function findCatalogItems() {
            if (!document.body) return [];
            const containers = [...document.querySelectorAll(CATALOG_CONTAINERS), document.body];
            const pageHint = document.title + ' ' + location.pathname + ' ' + visible(document.body.querySelector('h1,h2,h3') || document.body);
            let best = [];
            let bestScore = -1;
            for (const container of containers) {
              const items = collectCatalogItems(container);
              if (items.length < 4) continue;
              const containerHint = container.id + ' ' + container.className;
              const marked = CATALOG_WORD.test(pageHint) || CATALOG_CONTAINER_WORD.test(containerHint);
              const bodyLooksLikeCatalog = container === document.body && items.length >= 8 && visible(document.body).length < items.length * 90;
              if (!marked && !bodyLooksLikeCatalog) continue;
              const score = items.length * 12 + (marked ? 240 : 0) + (container !== document.body ? 120 : 0);
              if (score > bestScore) { bestScore = score; best = items; }
            }
            return best;
          }

          function findCatalogPages() {
            const pages = [];
            const seen = new Set();
            for (const anchor of document.querySelectorAll('a[href]')) {
              if (anchor.closest('#rm-root') || hiddenTree(anchor)) continue;
              const href = urlFor(anchor);
              const label = labelFor(anchor);
              const inPageContainer = Boolean(anchor.closest(PAGE_CONTAINERS));
              const numeric = inPageContainer && pageNumber(label);
              if (!href || (!PAGE_WORD.test(label) && !numeric) || seen.has(href)) continue;
              seen.add(href);
              pages.push({ label, href });
            }
            return pages;
          }

          function findCandidate() {
            const selectors = ['article','main','[role="main"]','#chaptercontent','#chapter-content','#content','.chapter-content','.chapterContent','.read-content','.readContent','.reading-content','.novel-content','.article-content','.content','.txtnav','.book-text','.book-content','.text-content'];
            const candidates = [];
            const seen = new Set();
            for (const selector of selectors) {
              document.querySelectorAll(selector).forEach((element) => {
                if (!seen.has(element) && !element.closest('#rm-root') && !element.matches(NOISE)) { seen.add(element); candidates.push(element); }
              });
            }
            candidates.push(document.body);
            let best = document.body;
            let bestScore = -Infinity;
            for (const candidate of candidates) {
              const text = visible(candidate);
              const length = text.replace(/\s/g, '').length;
              if (length < 80) continue;
              const links = [...candidate.querySelectorAll('a')].reduce((sum, link) => sum + visible(link).length, 0);
              const hint = candidate.id + ' ' + candidate.className;
              const score = Math.min(length, 50000) + Math.min(candidate.querySelectorAll('p').length, 40) * 10 + (candidate.tagName === 'ARTICLE' || candidate.tagName === 'MAIN' ? 160 : 0) + (/(chapter|content|article|novel|read|book|text|正文|章节|小说|阅读|内容)/i.test(hint) ? 120 : 0) - (text.length ? links / text.length * 600 : 600) - candidate.querySelectorAll('.ad,.ads,.advertisement,nav,aside').length * 75;
              if (score > bestScore) { best = candidate; bestScore = score; }
            }
            return best;
          }

          function collectText(node) {
            if (node.nodeType === Node.TEXT_NODE) return node.nodeValue || '';
            if (node.nodeType !== Node.ELEMENT_NODE) return '';
            if (node.tagName === 'BR') return '\n';
            let output = '';
            node.childNodes.forEach((child) => { output += collectText(child); });
            if (BLOCKS.has(node.tagName)) output += '\n\n';
            return output;
          }
          function paragraphsFrom(raw) {
            const lines = raw.replace(/\r/g, '').replace(/\u00a0/g, ' ').split(/\n+/).map((line) => line.replace(/[ \t]+/g, ' ').trim()).filter(Boolean);
            const result = [];
            lines.forEach((line) => {
              if (/^(上一章|下一章|目录|章节目录|加入书签|收藏本书|投推荐票|章节报错)$/.test(line) || /^(手机用户请|请记住本书(首发)?(域名|网址)|本章未完|最新网址)/.test(line) || /^(https?:\/\/|www\.)/i.test(line) || /^点击(下一页|阅读|下载|继续)/.test(line)) return;
              if (result.length && line.length < 5 && !/[。！？.!?：:]$/.test(result[result.length - 1])) result[result.length - 1] += line;
              else result.push(line);
            });
            return result;
          }
          function headingFor(candidate) {
            const heading = candidate.querySelector('h1,h2,h3,.chapter-title,.chapterTitle,.title');
            return heading ? clean(heading.textContent) : '';
          }
          function titleForCatalog() {
            const headings = [...document.querySelectorAll('h1,h2,h3')].map((item) => clean(item.textContent)).filter((item) => item.length >= 2 && item.length <= 120);
            const heading = headings.find((item) => !/^((章节)?目录|catalog|contents?)$/i.test(item));
            const title = clean(document.title).replace(/\s*[-|｜·•]\s*(目录|章节目录|catalog|contents?)\s*$/i, '').trim();
            return heading || title || headings[0] || '章节目录';
          }
          function sameLink(anchor, patterns) {
            const text = labelFor(anchor);
            const descriptor = text + ' ' + anchor.id + ' ' + anchor.className + ' ' + (anchor.getAttribute('aria-label') || '');
            const rel = (anchor.rel || '').toLowerCase();
            return patterns.rel.test(rel) || patterns.text.test(text) || patterns.hint.test(descriptor);
          }
          function findNavigation() {
            const result = { previous: null, next: null, catalog: null };
            const score = { previous: -Infinity, next: -Infinity, catalog: -Infinity };
            const patterns = {
              previous: { rel: /(^|\s)(prev|previous|back)(\s|$)/i, text: /^(上一章|上章|上一节|前一章|上一页|prev(?:ious)?|back)$/i, hint: /上一章|上一节|前一章|上一页/i },
              next: { rel: /(^|\s)(next|continue)(\s|$)/i, text: /^(下一章|下章|下一节|后一章|下一页|next|continue)$/i, hint: /下一章|下一节|后一章|下一页/i },
              catalog: { rel: /(^|\s)(contents?|catalog)(\s|$)/i, text: /^(目录|章节目录|返回目录|书目|目录页|catalog|contents?)$/i, hint: /目录|书目|catalog|contents?/i }
            };
            for (const anchor of document.querySelectorAll('a[href]')) {
              if (anchor.closest('#rm-root') || hidden(anchor)) continue;
              const href = urlFor(anchor);
              if (!href) continue;
              for (const kind of Object.keys(patterns)) {
                if (!sameLink(anchor, patterns[kind])) continue;
                const descriptor = labelFor(anchor) + ' ' + anchor.id + ' ' + anchor.className;
                const value = patterns[kind].text.test(labelFor(anchor)) ? 100 : 50 + (patterns[kind].hint.test(descriptor) ? 30 : 0);
                if (value > score[kind]) { score[kind] = value; result[kind] = { label: labelFor(anchor), href }; }
              }
            }
            return result;
          }

          const candidate = findCandidate();
          const catalogItems = findCatalogItems();
          const catalogPages = catalogItems.length ? findCatalogPages() : [];
          const isCatalog = catalogItems.length >= 4;
          const heading = headingFor(candidate);
          const title = isCatalog ? titleForCatalog() : (heading || clean(document.title) || '未识别标题');
          const clone = candidate.cloneNode(true);
          const originalNodes = [candidate, ...candidate.querySelectorAll('*')];
          const cloneNodes = [clone, ...clone.querySelectorAll('*')];
          for (let i = 1; i < Math.min(originalNodes.length, 3000); i += 1) if (cloneNodes[i] && hidden(originalNodes[i])) cloneNodes[i].remove();
          clone.querySelectorAll(NOISE).forEach((element) => element.remove());
          const paragraphs = paragraphsFrom(collectText(clone));
          const titleKey = clean(title).replace(/\s/g, '');
          const duplicate = paragraphs.findIndex((item, index) => index < 3 && clean(item).replace(/\s/g, '') === titleKey);
          if (duplicate >= 0) paragraphs.splice(duplicate, 1);
          return JSON.stringify({ sourceUrl: location.href, title, paragraphs: isCatalog ? [] : paragraphs, catalogItems: isCatalog ? catalogItems : [], catalogPages: isCatalog ? catalogPages : [], navigation: findNavigation() });
        })()
    """.trimIndent()
}
