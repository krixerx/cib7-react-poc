#!/usr/bin/env node
/**
 * Builds the vendorable copy of the service-builder skill for a core release,
 * the artifact a pack repository unpacks into .claude/skills/service-builder/.
 *
 *   node scripts/package-service-builder.mjs --version 2.0.0 [--out dist/service-builder]
 *     [--repo https://github.com/<owner>/<repo>]
 *
 * In the core repository the skill links to the reference pack and to core
 * files by relative path; in a pack repository neither is there. So every
 * link that leaves the skill folder is rewritten to the core repository at
 * the release tag (the reference pack lives there too), except the spec
 * template's links into the pack it is copied into, which stay relative. The
 * diagram tool goes into tools/, and VERSION names the core release and the
 * platform API the skill writes for. A link to a file that does not exist
 * fails the build, so a release never ships a dead reference.
 */
import { execFileSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, posix, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const core = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const SKILL = '.claude/skills/service-builder';
const PACK = 'packs/reference';
/** Where a copied spec template lands in a pack, for its relative links. */
const SPEC_DEST = `${PACK}/docs/business/services/<service-id>`;

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}

const version = arg('version');
if (!version || !/^\d+\.\d+\.\d+$/.test(version)) {
  console.error('usage: package-service-builder.mjs --version <x.y.z> [--out dir] [--repo url]');
  process.exit(2);
}
const out = resolve(arg('out', join(core, 'dist/service-builder')));
const repo = (
  arg('repo') ??
  (process.env.GITHUB_REPOSITORY
    ? `https://github.com/${process.env.GITHUB_REPOSITORY}`
    : execFileSync('git', ['-C', core, 'remote', 'get-url', 'origin'], { encoding: 'utf8' }))
)
  .trim()
  .replace(/\.git$/, '');
const blob = `${repo}/blob/v${version}`;

const problems = [];

/** Repo path of a link target, or null for URLs and in-page anchors. */
function repoPath(fromDir, target) {
  if (/^[a-z]+:/i.test(target) || target.startsWith('#')) return null;
  const [path, fragment] = target.split('#');
  return { path: posix.normalize(posix.join(fromDir, path)), fragment };
}

function exists(path) {
  return path.includes('<') || existsSync(join(core, path));
}

/**
 * Rewrites a markdown file's links. `fromDir` is where the file sits for its
 * relative links; `keepInside` is the repo prefix whose targets stay relative.
 */
function rewrite(file, fromDir, keepInside, keepFrom) {
  const text = readFileSync(file, 'utf8');
  const result = text.replace(/\]\(([^)\s]+)\)/g, (whole, target) => {
    const resolved = repoPath(fromDir, target);
    if (!resolved) return whole;
    const { path, fragment } = resolved;
    if (path.startsWith('..')) {
      problems.push(`${relative(out, file)}: ${target} leaves the repository`);
      return whole;
    }
    if (!exists(path)) {
      problems.push(`${relative(out, file)}: ${target} -> ${path} does not exist`);
      return whole;
    }
    if (keepInside && (path === keepInside || path.startsWith(`${keepInside}/`))) {
      const rel = posix.relative(keepFrom, path) || '.';
      return `](${rel}${fragment ? `#${fragment}` : ''})`;
    }
    return `](${blob}/${path}${fragment ? `#${fragment}` : ''})`;
  });
  writeFileSync(file, result);
}

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    return statSync(p).isDirectory() ? walk(p) : [p];
  });
}

rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });
cpSync(join(core, SKILL), out, { recursive: true });

// SKILL.md: links into the skill folder (spec-template/) stay relative.
rewrite(join(out, 'SKILL.md'), SKILL, SKILL, SKILL);

// The spec template: its links are written for its place in a pack, so a
// target inside the pack stays relative and anything else is the core's.
for (const file of walk(join(out, 'spec-template')).filter((f) => f.endsWith('.md'))) {
  const sub = posix.dirname(relative(join(out, 'spec-template'), file).split('\\').join('/'));
  const fromDir = posix.normalize(posix.join(SPEC_DEST, sub));
  rewrite(file, fromDir, PACK, fromDir);
}

mkdirSync(join(out, 'tools'));
for (const f of ['bpmn-to-mermaid.mjs', 'package.json', 'package-lock.json']) {
  cpSync(join(core, 'scripts', f), join(out, 'tools', f));
}

const platform = readFileSync(join(core, 'docs/platform-api.md'), 'utf8').match(
  /\*\*Platform API version:\*\* `([0-9.]+)`/,
)?.[1];
if (!platform) problems.push('docs/platform-api.md: no platform API version');
writeFileSync(
  join(out, 'VERSION'),
  `core ${version}\nplatform-api ${platform}\nsource ${blob}/${SKILL}\n`,
);

if (problems.length > 0) {
  console.error(`package-service-builder: ${problems.length} problem(s)\n  ${problems.join('\n  ')}`);
  process.exit(1);
}
console.log(`package-service-builder: ${out} (core ${version}, platform API ${platform})`);
