// Validates that ReaderScript.extract is still syntactically valid JavaScript.
// The extraction script lives inside a Kotlin raw string, so a stray brace would only
// surface at runtime as a silent evaluateJavascript failure. Run this before building:
//
//   node tools/check-reader-script.mjs
import { readFile, writeFile, mkdtemp, rm } from 'node:fs/promises';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';

const here = dirname(fileURLToPath(import.meta.url));
const sourcePath = join(here, '..', 'app', 'src', 'main', 'java', 'com', 'example', 'jingdu', 'ReaderScript.kt');
const source = await readFile(sourcePath, 'utf8');

const match = source.match(/val extract = """([\s\S]*?)"""\.trimIndent\(\)/);
if (!match) {
  console.error('ReaderScript.extract was not found; the raw-string layout changed.');
  process.exit(1);
}

const scripts = { 'extract.js': match[1] };
const challenge = source.match(/val challengeProbe = """([\s\S]*?)"""\.trimIndent\(\)/);
if (challenge) scripts['challengeProbe.js'] = challenge[1];

const directory = await mkdtemp(join(tmpdir(), 'jingdu-reader-script-'));
let failed = false;
try {
  for (const [name, body] of Object.entries(scripts)) {
    const file = join(directory, name);
    await writeFile(file, body, 'utf8');
    const result = spawnSync(process.execPath, ['--check', file], { encoding: 'utf8' });
    if (result.status === 0) {
      console.log(`ok   ${name} (${body.length} chars)`);
    } else {
      failed = true;
      console.error(`FAIL ${name}\n${result.stderr || result.stdout}`);
    }
  }
} finally {
  await rm(directory, { recursive: true, force: true });
}

process.exit(failed ? 1 : 0);
