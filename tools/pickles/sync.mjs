// Copy the Pickles artwork from its repository into this project, pinned to one commit.
//
//   node tools/pickles/sync.mjs <maxlyth/pickles checkout> <commit>   copy pickles.svg byte for byte and record the pin
//   node tools/pickles/sync.mjs --check                               fail when the copy is not the pinned file
//
// upstream.json beside this script records the commit and the SHA-256 of the file at it. A hand
// edit or a stale copy fails the check, so a refresh is that one command plus a commit.
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const copy = new URL('../../app/src/main/assets/pickles.svg', import.meta.url);
const pinFile = new URL('./upstream.json', import.meta.url);
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const [checkout, ref] = process.argv.slice(2);

if (checkout === '--check') {
  const pin = JSON.parse(readFileSync(pinFile, 'utf8'));
  if (sha256(readFileSync(copy)) !== pin.sha256) {
    console.error(`pickles: ${copy.pathname} is not ${pin.repository}/pickles.svg at ${pin.commit}; run tools/pickles/sync.mjs <checkout> <commit>`);
    process.exit(1);
  }
  console.log(`pickles: copy matches ${pin.repository} at ${pin.commit}`);
} else if (checkout && ref) {
  const commit = execFileSync('git', ['-C', checkout, 'rev-parse', '--verify', `${ref}^{commit}`], { encoding: 'utf8' }).trim();
  const svg = execFileSync('git', ['-C', checkout, 'show', `${commit}:pickles.svg`], { maxBuffer: 1 << 24 });
  writeFileSync(copy, svg);
  writeFileSync(pinFile, JSON.stringify({ repository: 'maxlyth/pickles', commit, sha256: sha256(svg) }, null, 2) + '\n');
  console.log(`pickles: synced ${svg.length} bytes at ${commit}`);
} else {
  console.error('usage: node tools/pickles/sync.mjs <maxlyth/pickles checkout> <commit> | --check');
  process.exit(2);
}
