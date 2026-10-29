import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..');
const source = readFileSync(resolve(root, 'tools/deploy-worker/worker.js'), 'utf8').trimEnd();

function update(path, content) {
  const file = resolve(root, path);
  if (readFileSync(file, 'utf8') !== content) writeFileSync(file, content);
}

function embedded(path, before, after) {
  const file = resolve(root, path);
  const content = readFileSync(file, 'utf8');
  const start = content.indexOf(before);
  const end = content.indexOf(after, start + before.length);
  if (start < 0 || end < 0) throw new Error(`Worker markers missing in ${path}`);
  update(path, content.slice(0, start + before.length) + source + content.slice(end));
}

update('app/src/main/assets/worker.js', source + '\n');
update('docs/cloudflare_worker.js', source + '\n');
embedded('tools/deploy-worker/deploy.ps1', "$WorkerJsCode = @'\n", "\n'@");
embedded('tools/deploy-worker/deploy.sh', "cat << 'EOF' > \"$TEMP_DIR/worker.js\"\n", '\nEOF');
embedded('core/src/main/kotlin/com/mirrly/tgproxy/core/TgConstants.kt',
  'const val CLOUDFLARE_WORKER_JS_CODE = """', '"""');
