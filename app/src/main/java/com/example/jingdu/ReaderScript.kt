package com.example.jingdu

object ReaderScript {
    val challengeProbe = """
        (() => JSON.stringify({
          title: document.title || '',
          text: (document.body?.innerText || '').slice(0, 800)
        }))()
    """.trimIndent()

    // Collects search result entries so the search tab can offer "save to shelf" without opening a
    // dedicated API. Handles Bing/Baidu result blocks, then falls back to a generic pass.
    val searchResults = """
        (() => {
          const out = [];
          const seen = new Set();
          const engine = /bing\./i.test(location.host) ? 'bing' : (/baidu\./i.test(location.host) ? 'baidu' : 'other');
          const blocked = /bing\.com|baidu\.com|google\.|microsoft\.com|msn\.com|w3\.org|schema\.org|creativecommons|mozilla\.org/;
          const push = (title, href) => {
            const name = String(title || '').replace(/\s+/g, ' ').trim();
            if (!name || name.length < 2 || name.length > 90) return;
            if (!href || !/^https?:/i.test(href)) return;
            if (blocked.test(href)) return;
            if (seen.has(href)) return;
            seen.add(href);
            out.push({ title: name, href });
          };
          const blocks = document.querySelectorAll(
            '#b_results > li.b_algo, #b_results > li.b_ans, #b_results > li, .b_algo, .result, .c-container, .result-op'
          );
          blocks.forEach((block) => {
            const anchors = block.querySelectorAll('h2 a[href], h3 a[href], a[href]');
            let anchor = null;
            for (const candidate of anchors) {
              if (!blocked.test(candidate.href || '')) { anchor = candidate; break; }
            }
            if (!anchor) return;
            const heading = block.querySelector('h2, h3');
            const headingText = heading ? heading.textContent : '';
            const cite = block.querySelector('cite');
            const citeText = cite ? cite.textContent : '';
            // Prefer the headline, but fall back to the cited site name so ads and cards still count.
            push(headingText || citeText || anchor.textContent, anchor.href);
          });
          if (out.length < 5) {
            document.querySelectorAll('h2 a[href], h3 a[href]').forEach((anchor) => {
              push(anchor.textContent, anchor.href);
            });
          }
          return JSON.stringify({ url: location.href, engine: engine, title: document.title || '', results: out.slice(0, 40) });
        })()
    """.trimIndent()

    val extract = """
        (() => {
          const NOISE = 'script,style,noscript,template,iframe,canvas,svg,nav,aside,header,footer,form,button,input,textarea,select,option,[role="navigation"],[role="complementary"],[aria-hidden="true"],[hidden],.topbar,.header,.nav,.m-nav,.m-setting,.footer,.hotcmd-wp,.hotcmd-box,.ad,.ads,.advert,.advertisement,.adsbygoogle,.ad-container,[class*="ad-"],[class*="-ad"],[id*="ad-"],[id*="-ad"],.popup,.modal,.overlay,.recommend,.recommendation,.related,.share,.social,.comment,.comments,.toolbar,.pagination,.chapter-nav,.breadcrumb,.notice,.copyright';
          const CATALOG_CONTAINERS = '#list,#catalog,#chapter-list,#chapterList,#目录,.catalog,.catalog-list,.chapter-list,.chapterList,.chapter-list-box,.book-list,.book-chapter-list,.listmain,.volume-list,.directory,.section-box,.section-list,[class*="chapter-list"],[class*="chapter_list"],[class*="chapterlist"],[id*="chapter-list"],[id*="chapterlist"],[class*="volume"],[id*="volume"]';
          const PAGE_CONTAINERS = '.pagination,.pager,.pages,.page,.page-list,.pageList,.listpage,[class*="pagination"],[class*="pager"],[class*="page-list"],[class*="listpage"],[id*="pagination"],[id*="pager"],[id*="page-list"]';
          const CATALOG_WORD = /(目录|章节目录|目录页|书目|章节列表|最新章节|全部章节|catalog|contents?)/i;
          const CATALOG_CONTAINER_WORD = /(catalog|chapter[-_]?list|directory|listmain|section-list|section-box|目录|章节)/i;
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
          function visibilityHidden(element) {
            if (element.hidden || element.getAttribute('aria-hidden') === 'true') return true;
            try {
              const computed = getComputedStyle(element);
              return computed.visibility === 'hidden' || computed.opacity === '0';
            } catch (_) { return false; }
          }
          function collectAnchors() {
            const anchors = [];
            for (const anchor of document.querySelectorAll('a[href]')) {
              if (anchor.closest('#rm-root')) continue;
              if (anchor.closest(NOISE)) continue;
              if (visibilityHidden(anchor)) continue;
              anchors.push(anchor);
            }
            return anchors;
          }
          // Mobile layouts commonly hide the text-pagination bar with display:none while the pages
          // themselves still exist; expose those blocks for link discovery, then restore them.
          function revealHiddenNavigation() {
            const elements = [...document.querySelectorAll('div,section,ul,ol,p,span')];
            const restored = [];
            for (const element of elements) {
              if (element.closest('#rm-root')) continue;
              let display = '';
              try { display = getComputedStyle(element).display; } catch (_) { display = ''; }
              if (display !== 'none') continue;
              const text = clean(element.textContent || '');
              if (!/(上一页|下一页|上一章|下一章|章节列表|目录)/.test(text)) continue;
              if (text.length > 200) continue;
              restored.push({ element, style: element.getAttribute('style') });
              element.style.setProperty('display', 'block', 'important');
            }
            return restored;
          }
          function hideNavigation(element, restored) {
            for (const entry of restored) {
              element.querySelectorAll('div,section,ul,ol,p,span').forEach((node) => {
                const match = restored.find((item) => item.element === node);
                if (!match) return;
                if (match.style == null) node.removeAttribute('style');
                else node.setAttribute('style', match.style);
              });
            }
          }
          function tagIdHint(element) {
            return ((element.id || '') + ' ' + (typeof element.className === 'string' ? element.className : '')).trim();
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
            const pageLooksLikeChapter = CHAPTER_WORD.test(document.title) ||
              [...document.querySelectorAll('h1,h2,h3,.chapter-title,.chapterTitle,.title')]
                .some((heading) => CHAPTER_WORD.test(clean(heading.textContent)));
            const chapterAnchorCount = (container) =>
              [...container.querySelectorAll('a[href]')].filter((anchor) => {
                const label = labelFor(anchor);
                return label.length >= 2 && label.length <= 140 && !PAGE_WORD.test(label) && CHAPTER_WORD.test(label);
              }).length;
            const scopeAnchorCount = (container) =>
              [...container.querySelectorAll('a[href]')].filter((anchor) => labelFor(anchor).length >= 2).length;
            let best = [];
            let bestScore = -1;
            for (const container of containers) {
              const items = collectCatalogItems(container);
              if (!items.length) continue;
              const containerHint = container.id + ' ' + container.className;
              const marked = CATALOG_WORD.test(pageHint) || CATALOG_CONTAINER_WORD.test(containerHint);
              const bodyLooksLikeCatalog = container === document.body && items.length >= 8 && visible(document.body).length < items.length * 90;
              const hasPaginationHint = [...container.querySelectorAll('a[href]')].some((anchor) => {
                const label = labelFor(anchor);
                return PAGE_WORD.test(label) || pageNumber(label);
              });
              if (container === document.body && items.length < 4 && !hasPaginationHint && pageLooksLikeChapter) continue;
              // A chapter page can contain a sidebar such as "related novels"; a real chapter list
              // holds many chapter-styled links, so a link-heavy but chapter-poor block is not a catalog.
              if (container !== document.body && pageLooksLikeChapter && chapterAnchorCount(container) < 4) continue;
              if (!marked && !bodyLooksLikeCatalog && !hasPaginationHint) continue;
              let score = items.length * 12 + (marked ? 240 : 0) + (container !== document.body ? 120 : 0);
              // "Latest chapters" teasers look like a catalog but only repeat a few chapters; prefer
              // the sibling or parent block that actually carries the full list.
              const parent = container.parentElement;
              const neighbours = parent ? [parent, ...parent.children] : [];
              for (const other of neighbours) {
                if (other === container || other.tagName !== 'DIV' && other.tagName !== 'UL' && other.tagName !== 'SECTION' && other.tagName !== 'MAIN') continue;
                const otherIds = (other.id || '') + ' ' + (typeof other.className === 'string' ? other.className : '');
                if (!CATALOG_CONTAINER_WORD.test(otherIds)) continue;
                if (scopeAnchorCount(other) >= Math.max(8, items.length * 2)) score -= 400;
              }
              if (score > bestScore) { bestScore = score; best = items; }
            }
            return best;
          }

          function samePageFamily(href) {
            try {
              const left = location.pathname.replace(/[^/]*$/, '');
              const right = new URL(href, location.href).pathname.replace(/[^/]*$/, '');
              return left === right;
            } catch (_) { return false; }
          }
          function findCatalogPageOptions() {
            const pages = [];
            const seen = new Set();
            document.querySelectorAll('select').forEach((select) => {
              const container = select.closest(PAGE_CONTAINERS);
              const hint = ((select.name || '') + ' ' + (select.id || '') + ' ' + (select.className || '')).toLowerCase();
              if (!container && !/page|pageno|pagenum|pagelist|select/.test(hint)) return;
              select.querySelectorAll('option').forEach((option) => {
                const raw = (option.value || option.getAttribute('data-href') || option.getAttribute('data-url') || '').trim();
                if (!raw || /^(#|javascript:)/i.test(raw)) return;
                const href = urlFor({ getAttribute: () => raw, href: raw });
                if (!href || !samePageFamily(href) || seen.has(href)) return;
                seen.add(href);
                pages.push({ label: clean(option.textContent) || raw, href });
              });
            });
            return pages;
          }
          function findCatalogPages() {
            const pages = [];
            const seen = new Set();
            for (const anchor of collectAnchors()) {
              const href = urlFor(anchor);
              const label = labelFor(anchor);
              const inPageContainer = Boolean(anchor.closest(PAGE_CONTAINERS));
              const numeric = inPageContainer && pageNumber(label);
              if (!href || (!PAGE_WORD.test(label) && !numeric) || seen.has(href)) continue;
              seen.add(href);
              pages.push({ label, href });
            }
            findCatalogPageOptions().forEach((page) => {
              if (seen.has(page.href)) return;
              seen.add(page.href);
              pages.push(page);
            });
            return pages;
          }

          function findCandidate() {
            const selectors = ['article','main','[role="main"]','.reader-main','.read-main','#chaptercontent','#chapter-content','#content','.chapter-content','.chapterContent','.read-content','.readContent','.reading-content','.novel-content','.article-content','.content','.con','.txtnav','.book-text','.book-content','.text-content'];
            const preferred = ['#content','#chaptercontent','#chapter-content','.chapter-content','.chapterContent','.read-content','.readContent','.reading-content','.novel-content','.article-content','.content','.con','.txtnav','.book-text','.book-content','.text-content'];
            for (const selector of preferred) {
              const element = document.querySelector(selector);
              if (element && visible(element).replace(/\\s/g, '').length >= 80) return element;
            }
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
              if (/^(上一章|下一章|上一页|下一页|上页|下页|前页|后页|目录|书页(?:\/|\s*)目录|章节目录|加入书签|收藏本书|投推荐票|章节报错|没有了|书末页)$/i.test(line) ||
                   (/^(上一章|下一章)\s*[:：]?/.test(line) && line.length < 260) ||
                   /^(手机用户请|请记住本书(首发)?(域名|网址)|本章未完|最新网址)/.test(line) ||
                   /^(https?:\/\/|www\.)/i.test(line) || /^点击(下一页|阅读|下载|继续)/.test(line) ||
                   /如果被.{0,100}(强制|进入).{0,100}(阅读模式|转码阅读)|阅读体验极差请退出转码阅读/.test(line)) return;
              if (result.length && line.length < 5 && !/[。！？.!?：:]$/.test(result[result.length - 1])) result[result.length - 1] += line;
              else result.push(line);
            });
            return result;
          }
          function headingFor(candidate) {
            const selectors = ['h1','h2','.chapter-title','.chapterTitle','.title','h3'];
            for (const selector of selectors) {
              const local = candidate.querySelector(selector);
              if (!local) continue;
              const text = clean(local.textContent);
              if (text && CHAPTER_WORD.test(text)) return text;
            }
            const local = candidate.querySelector(selectors.join(','));
            const localText = local ? clean(local.textContent) : '';
            if (localText) return localText;
            return [...document.querySelectorAll('h1,h2,h3,.chapter-title,.chapterTitle,.title')]
              .map((item) => clean(item.textContent))
              .find((item) => CHAPTER_WORD.test(item)) || '';
          }
          function titleForCatalog() {
            const headings = [...document.querySelectorAll('h1,h2,h3')].map((item) => clean(item.textContent)).filter((item) => item.length >= 2 && item.length <= 120);
            const heading = headings.find((item) => !/^((章节)?目录|catalog|contents?)$/i.test(item));
            const title = clean(document.title).replace(/\s*[-|｜·•]\s*(目录|章节目录|catalog|contents?)\s*$/i, '').trim();
            return heading || title || headings[0] || '章节目录';
          }
          function sameLink(anchor, patterns) {
            const text = labelFor(anchor);
            const parent = anchor.parentElement;
            const ancestorHint = parent ? ((parent.id || '') + ' ' + (typeof parent.className === 'string' ? parent.className : '')) : '';
            const descriptor = text + ' ' + anchor.id + ' ' + anchor.className + ' ' +
              (anchor.getAttribute('aria-label') || '') + ' ' + ancestorHint;
            const rel = (anchor.rel || '').toLowerCase();
            return patterns.rel.test(rel) || patterns.text.test(text) || patterns.hint.test(descriptor);
          }
          function findNavigation() {
            const result = { previous: null, next: null, catalog: null, previousPage: null, nextPage: null };
            const score = { previous: -Infinity, next: -Infinity, catalog: -Infinity, previousPage: -Infinity, nextPage: -Infinity };
            const patterns = {
              previous: { rel: /(^|\s)(prev|previous|back)(\s|$)/i, text: /^(上一章|上章|上一节|前一章|前一回|prev(?:ious)?|back)$/i, hint: /上一章|上一节|前一章|前一回/i },
              next: { rel: /(^|\s)(next|continue)(\s|$)/i, text: /^(下一章|下章|下一节|后一章|后一回|next|continue)$/i, hint: /下一章|下一节|后一章|后一回/i },
              previousPage: { rel: /(^|\s)(page[-_ ]?prev|prev[-_ ]?page)(\s|$)/i, text: /^(上一页|上页|前页|prev(?:ious)?\s*page)$/i, hint: /上一页|上页|前页/i },
              nextPage: { rel: /(^|\s)(page[-_ ]?next|next[-_ ]?page)(\s|$)/i, text: /^(下一页|下页|后页|next\s*page)$/i, hint: /下一页|下页|后页/i },
              catalog: { rel: /(^|\s)(contents?|catalog)(\s|$)/i, text: /^(目录|章节目录|返回目录|返回书页|回到书页|书目|目录页|章节列表|全部章节|查看目录|本书目录|book\s*list|catalog|contents?)$/i, hint: /目录|书目|章节列表|全部章节|book[-_]?list|catalog|contents?/i }
            };
            const anchors = collectAnchors();
            for (const anchor of anchors) {
              const href = urlFor(anchor);
              if (!href) continue;
              const label = labelFor(anchor);
              const descriptor = label + ' ' + anchor.id + ' ' + anchor.className + ' ' + (anchor.getAttribute('aria-label') || '');
              const pageKind = patterns.previousPage.text.test(label) || patterns.previousPage.hint.test(descriptor)
                ? 'previousPage'
                : patterns.nextPage.text.test(label) || patterns.nextPage.hint.test(descriptor)
                  ? 'nextPage'
                  : null;
              if (pageKind) {
                if (patterns[pageKind].text.test(label) && score[pageKind] < 120) {
                  score[pageKind] = 120;
                  result[pageKind] = { label, href };
                } else if (score[pageKind] < 60) {
                  score[pageKind] = 60;
                  result[pageKind] = { label, href };
                }
                continue;
              }
              for (const kind of ['previous', 'next', 'catalog']) {
                if (!sameLink(anchor, patterns[kind])) continue;
                const value = patterns[kind].text.test(label) ? 100 : 50 + (patterns[kind].hint.test(descriptor) ? 30 : 0);
                if (value > score[kind]) { score[kind] = value; result[kind] = { label, href }; }
              }
            }
            if (result.catalog == null) {
              // Sites often expose the book page through a breadcrumb to the work itself, e.g.
              // "<site> > <book title>" pointing at the catalog root from a chapter page.
              const folder = location.pathname.split('/').filter(Boolean).slice(0, -1).join('/');
              if (folder) {
                const candidate = anchors
                  .map((anchor) => ({ label: labelFor(anchor), href: urlFor(anchor), element: anchor }))
                  .filter((item) => {
                    if (!item.href) return false;
                    if (item.label.length < 2 || item.label.length > 80) return false;
                    if (PAGE_WORD.test(item.label) || CHAPTER_WORD.test(item.label)) return false;
                    if (item.element.closest('ul,ol,select,.nav,.topbar,.header,.footer,.m-nav,.hotcmd-wp,.cmd-bd,.menu,.listpage')) return false;
                    const target = new URL(item.href);
                    if (target.origin !== location.origin || target.search || !target.pathname.endsWith('/')) return false;
                    const segments = target.pathname.split('/').filter(Boolean);
                    if (segments.length !== 1 || segments[0] !== folder) return false;
                    return item.href !== location.href;
                  })
                  .sort((left, right) => left.href.length - right.href.length);
                if (candidate.length) result.catalog = { label: candidate[0].label, href: candidate[0].href };
              }
            }
            return result;
          }

          const browserErrorText = clean((document.title || '') + ' ' + visible(document.body));
          if (/ERR_[A-Z_]+|网页无法打开|无法加载此网页|无法连接到该网站|This site can.?t be reached|connection refused|安全验证|checking your browser|just a moment|verify you are human|banned you temporarily|access denied/i.test(browserErrorText)) {
            return JSON.stringify({ sourceUrl: location.href, title: '网页无法打开', paragraphs: [], catalogItems: [], catalogPages: [], navigation: findNavigation() });
          }
          const candidate = findCandidate();
          const restoredNavigation = revealHiddenNavigation();
          const catalogItems = findCatalogItems();
          const heading = headingFor(candidate);
          const headingLooksChapter = heading !== '' && CHAPTER_WORD.test(heading);
          const isCatalog = catalogItems.length > 0 && !headingLooksChapter;
          // Paginated catalogs (numbered pages, "next page" links, page <select>) feed the catalog
          // crawler; chapter-page text continuations stay in navigation.nextPage instead.
          const catalogPages = findCatalogPages();
          const navigation = findNavigation();
          const title = isCatalog ? titleForCatalog() : (heading || clean(document.title) || '未识别标题');
          hideNavigation(document, restoredNavigation);
          const clone = candidate.cloneNode(true);
          const originalNodes = [candidate, ...candidate.querySelectorAll('*')];
          const cloneNodes = [clone, ...clone.querySelectorAll('*')];
          for (let i = 1; i < Math.min(originalNodes.length, 3000); i += 1) if (cloneNodes[i] && hidden(originalNodes[i])) cloneNodes[i].remove();
          clone.querySelectorAll(NOISE).forEach((element) => element.remove());
          const paragraphs = paragraphsFrom(collectText(clone));
          const titleKey = clean(title).replace(/\s/g, '');
          const duplicate = paragraphs.findIndex((item, index) => index < 3 && clean(item).replace(/\s/g, '') === titleKey);
          if (duplicate >= 0) paragraphs.splice(duplicate, 1);
          return JSON.stringify({ sourceUrl: location.href, title, paragraphs: isCatalog ? [] : paragraphs, catalogItems: isCatalog ? catalogItems : [], catalogPages: isCatalog ? catalogPages : [], navigation });
        })()
    """.trimIndent()
}
