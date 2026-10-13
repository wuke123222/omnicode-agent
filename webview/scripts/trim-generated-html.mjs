import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const target = fileURLToPath(new URL('../../src/main/resources/webview/index.html', import.meta.url));
const source = await readFile(target, 'utf8');
const normalized = source.replace(/[\t ]+(?=\r?\n)/g, '');
if (normalized !== source) await writeFile(target, normalized, 'utf8');
