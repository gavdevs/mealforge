import assert from 'node:assert/strict';
import { type SpawnSyncReturns, spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { test } from 'node:test';

const root = resolve(import.meta.dirname, '..');
const script = join(root, 'scripts/bootstrap-sdk.sh');
const source = process.env.LIGHT_SDK_FIXTURE_REPO ?? join(root, '.sdk');
const pin = '251237ef3778c907c5dbd89e84685532c9d99a28';

function git(directory: string, ...args: string[]): string {
  const result = spawnSync('git', ['-C', directory, ...args], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}

function provision(directory: string): SpawnSyncReturns<string> {
  return spawnSync('bash', [script], {
    env: { ...process.env, LIGHT_SDK_REPO: source, LIGHT_SDK_DIR: directory },
    encoding: 'utf8',
  });
}

function fixture(run: (directory: string) => void): void {
  const directory = mkdtempSync(join(tmpdir(), 'mealforge-sdk-check-'));
  try {
    run(join(directory, 'sdk'));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
}

function cloneWithoutCheckout(directory: string): void {
  const result = spawnSync('git', ['clone', '--no-checkout', source, directory], {
    encoding: 'utf8',
  });
  assert.equal(result.status, 0, result.stderr);
}

test('fresh provisioning populates the exact pin and leaves a clean checkout unchanged on repeat', () => {
  fixture((directory) => {
    const first = provision(directory);
    assert.equal(first.status, 0, `${first.stdout}\n${first.stderr}`);
    assert.ok(existsSync(join(directory, 'gradlew')));
    assert.equal(git(directory, 'rev-parse', 'HEAD'), pin);
    assert.equal(git(directory, 'status', '--porcelain'), '');
    const before = statSync(join(directory, 'README.md')).mtimeMs;
    const contents = readFileSync(join(directory, 'README.md'));
    const second = provision(directory);
    assert.equal(second.status, 0, `${second.stdout}\n${second.stderr}`);
    assert.equal(statSync(join(directory, 'README.md')).mtimeMs, before);
    assert.deepEqual(readFileSync(join(directory, 'README.md')), contents);
    assert.equal(git(directory, 'status', '--porcelain'), '');
  });
});

test('recovers a no-checkout clone whose HEAD already equals the pin without treating it as owner edits', () => {
  fixture((directory) => {
    cloneWithoutCheckout(directory);
    assert.equal(git(directory, 'rev-parse', 'HEAD'), pin);
    assert.equal(git(directory, 'ls-files'), '');
    const result = provision(directory);
    assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
    assert.ok(existsSync(join(directory, 'gradle/libs.versions.toml')));
    assert.equal(git(directory, 'status', '--porcelain'), '');
  });
});

test('rejects an edited checkout even at a different revision without replacing its files or HEAD', () => {
  fixture((directory) => {
    cloneWithoutCheckout(directory);
    git(directory, 'checkout', '--detach', `${pin}^`);
    const beforeHead = git(directory, 'rev-parse', 'HEAD');
    const file = join(directory, 'README.md');
    writeFileSync(file, 'owner edits must survive\n');
    const beforeStatus = git(directory, 'status', '--porcelain');
    const result = provision(directory);
    assert.notEqual(result.status, 0);
    assert.equal(readFileSync(file, 'utf8'), 'owner edits must survive\n');
    assert.equal(git(directory, 'rev-parse', 'HEAD'), beforeHead);
    assert.equal(git(directory, 'status', '--porcelain'), beforeStatus);
  });
});

test('rejects untracked files in an unpopulated clone instead of hydrating over them', () => {
  fixture((directory) => {
    cloneWithoutCheckout(directory);
    const file = join(directory, 'owner.txt');
    writeFileSync(file, 'keep me\n');
    const beforeStatus = git(directory, 'status', '--porcelain');
    const result = provision(directory);
    assert.notEqual(result.status, 0);
    assert.equal(readFileSync(file, 'utf8'), 'keep me\n');
    assert.equal(git(directory, 'status', '--porcelain'), beforeStatus);
    assert.equal(existsSync(join(directory, 'README.md')), false);
  });
});
