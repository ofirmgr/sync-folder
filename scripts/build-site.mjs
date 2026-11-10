import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { marked } from 'marked';

const root = fileURLToPath(new URL('../', import.meta.url));
const output = join(root, 'site-dist');
const origin = 'https://sync-folder.ofir.fun';
const sources = ['index.md', 'privacy-policy.md', 'terms-of-use.md', 'accessibility-statement.md'];
const pages = await Promise.all(sources.map(async (file) => {
  const source = await readFile(join(root, 'docs', file), 'utf8');
  const frontmatter = source.match(/^---\r?\n([\s\S]*?)\r?\n---\r?\n/);
  const content = source.slice(frontmatter?.[0].length ?? 0).trim();
  const title = content.match(/^# (.+)$/m)?.[1];
  const path = frontmatter?.[1].match(/^permalink: (.+)$/m)?.[1].trim() ?? '/';
  if (!title || !/^\/(?:[a-z-]+\/)?$/.test(path)) {
    throw new Error(`Invalid website title or permalink in docs/${file}`);
  }
  return { file, content, title, path };
}));

const escape = (text) => text.replace(/[&<>"']/g, (character) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[character]));
const links = pages.filter((page) => page.path !== '/').map((page) => {
  const label = page.title.replace('Sync Folder ', '');
  return `<a href="${page.path}">${escape(label)}</a>`;
}).join('\n');

for (const page of pages) {
  const markdown = page.content.replace(/\]\(([^)\s]+)\)/g, (match, href) => {
    const target = pages.find((candidate) => candidate.file === href);
    return target ? `](${target.path})` : match;
  });
  const html = `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escape(page.title)}</title>
  <meta name="description" content="Sync Folder uploads files from a folder you choose on Android to your Google Drive.">
  <link rel="canonical" href="${origin}${page.path}">
  <link rel="stylesheet" href="/styles.css">
</head>
<body>
  <a class="skip-link" href="#content">Skip to content</a>
  <header><a class="brand" href="/">Sync Folder</a><span>Android &amp; Google Drive</span></header>
  <main id="content" tabindex="-1" class="${page.path === '/' ? 'home' : 'document'}">
    ${marked.parse(markdown)}
  </main>
  <footer><nav aria-label="Information">${links}</nav><p>Published by Ofir Meguri · <a href="mailto:ofirmgr@gmail.com">ofirmgr@gmail.com</a></p></footer>
</body>
</html>
`;
  const destination = join(output, page.path, 'index.html');
  await mkdir(dirname(destination), { recursive: true });
  await writeFile(destination, html);
}

await copyFile(join(root, 'site', 'styles.css'), join(output, 'styles.css'));
// A standalone public artifact can be deployed without uploading the Android checkout.
await writeFile(join(output, 'vercel.json'), JSON.stringify({
  framework: null,
  installCommand: '',
  buildCommand: '',
  outputDirectory: '.',
  trailingSlash: true
}, null, 2) + '\n');
console.log(`Built ${pages.length} pages in site-dist for ${origin}`);
