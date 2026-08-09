#!/usr/bin/env node
// Internal-link checker for the generated site.
//
// Walks every HTML page in dist/, collects the link and resource attributes a browser would
// follow (href, src, srcset, action, poster, data-*-url) and verifies that each internal target
// exists in the build output. Fragments are checked too: "#foo" must match an id/name in the
// target document. External http(s) links are not fetched — this is an offline check of the
// site's own consistency, which is where the 404s come from.
//
// Usage: node check-links.mjs [distDir]
// Exits 1 and prints every broken target when something does not resolve.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, normalize, relative, resolve, sep } from 'node:path';

const DIST = resolve(process.argv[2] ?? join(dirname(new URL(import.meta.url).pathname), 'dist'));

// Hosts that mean "this site" — an absolute link to them is checked like a relative one.
const SITE_HOSTS = new Set(['metaloom.io', 'www.metaloom.io']);

// Targets that are legitimately absent from the build output.
const IGNORED_PREFIXES = ['mailto:', 'tel:', 'javascript:', 'data:', 'blob:', 'ftp:'];

/** Recursively list files under dir. */
function walk(dir, out = []) {
	for (const entry of readdirSync(dir, { withFileTypes: true })) {
		const full = join(dir, entry.name);
		if (entry.isDirectory()) walk(full, out);
		else if (entry.isFile()) out.push(full);
	}
	return out;
}

const allFiles = walk(DIST);
const htmlFiles = allFiles.filter((f) => f.endsWith('.html'));

/** Set of every path that exists in dist, as a site-absolute "/a/b" string. */
const existing = new Set(allFiles.map((f) => '/' + relative(DIST, f).split(sep).join('/')));

/** id/name anchors per HTML file, filled lazily. */
const anchorCache = new Map();

function anchorsOf(sitePath) {
	if (anchorCache.has(sitePath)) return anchorCache.get(sitePath);
	const ids = new Set();
	try {
		const html = readFileSync(join(DIST, sitePath), 'utf8');
		for (const m of html.matchAll(/\s(?:id|name)="([^"]+)"/g)) ids.add(m[1]);
	} catch {
		/* unreadable → treated as "no anchors" */
	}
	anchorCache.set(sitePath, ids);
	return ids;
}

/**
 * Resolve a site-absolute path to the file that serves it.
 * "/docs/" → "/docs/index.html", "/docs" → "/docs/index.html" or "/docs.html".
 * Returns null when nothing serves it.
 */
function resolveTarget(path) {
	if (existing.has(path)) return path;
	const candidates = path.endsWith('/')
		? [path + 'index.html']
		: [path + '/index.html', path + '.html', path + '/'];
	for (const c of candidates) if (existing.has(c)) return c;
	return null;
}

const attrPattern =
	/\s(href|src|srcset|action|poster|data-src|data-openapi-url|data-graphql-url|data-schema-url|data-search-index-url|data-search-vectors-url|data-search-model-url|data-search-wasm-url)="([^"]*)"/g;

const broken = [];

for (const file of htmlFiles) {
	const pageSitePath = '/' + relative(DIST, file).split(sep).join('/');
	const pageDir = dirname(pageSitePath);
	const html = readFileSync(file, 'utf8');

	for (const [, attr, rawValue] of html.matchAll(attrPattern)) {
		// srcset carries a comma separated candidate list ("a.webp 1x, b.webp 2x").
		const values =
			attr === 'srcset'
				? rawValue.split(',').map((c) => c.trim().split(/\s+/)[0])
				: [rawValue];

		for (const raw of values) {
			const value = raw.trim();
			if (!value || value === '#') continue;
			if (IGNORED_PREFIXES.some((p) => value.toLowerCase().startsWith(p))) continue;

			let path;
			let fragment = '';

			if (/^(https?:)?\/\//i.test(value)) {
				let url;
				try {
					url = new URL(value.startsWith('//') ? 'https:' + value : value);
				} catch {
					broken.push({ page: pageSitePath, attr, value, why: 'malformed URL' });
					continue;
				}
				if (!SITE_HOSTS.has(url.hostname)) continue; // external — not our problem
				path = url.pathname;
				fragment = decodeURIComponent(url.hash.slice(1));
			} else {
				const hashIndex = value.indexOf('#');
				const pathPart = hashIndex === -1 ? value : value.slice(0, hashIndex);
				fragment = hashIndex === -1 ? '' : decodeURIComponent(value.slice(hashIndex + 1));
				const queryless = pathPart.split('?')[0];
				if (!queryless) {
					// Pure "#anchor" link — resolves against the current page.
					path = pageSitePath;
				} else {
					const decoded = decodeURIComponent(queryless);
					path = decoded.startsWith('/')
						? normalize(decoded)
						: normalize(join(pageDir, decoded));
					// normalize() drops a trailing slash on "a/b/" only when it is "."; keep it.
					if (decoded.endsWith('/') && !path.endsWith('/')) path += '/';
				}
			}

			const served = resolveTarget(path);
			if (!served) {
				broken.push({ page: pageSitePath, attr, value, why: 'target does not exist' });
				continue;
			}
			if (fragment && served.endsWith('.html')) {
				if (!anchorsOf(served).has(fragment)) {
					broken.push({
						page: pageSitePath,
						attr,
						value,
						why: `page exists but has no "#${fragment}" anchor`
					});
				}
			}
		}
	}
}

if (broken.length) {
	console.error('');
	console.error(`ERROR: ${broken.length} broken internal link(s) in the build output:`);
	console.error('');
	const byPage = new Map();
	for (const b of broken) {
		if (!byPage.has(b.page)) byPage.set(b.page, []);
		byPage.get(b.page).push(b);
	}
	for (const [page, items] of [...byPage].sort()) {
		console.error(`  dist${page}`);
		for (const i of items) console.error(`    ${i.attr}="${i.value}"  →  ${i.why}`);
	}
	console.error('');
	console.error('Fix the source link (or add the missing page/anchor) — a published 404 is a bug.');
	process.exit(1);
}

console.log(`Link check OK — ${htmlFiles.length} pages, no broken internal links.`);
