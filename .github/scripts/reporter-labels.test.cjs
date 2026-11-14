const { test } = require('node:test');
const assert = require('node:assert/strict');
const handle = require('./reporter-labels.cjs');

function fixture(body = '/label bug') {
  const calls = [];
  const comments = [];
  const target = { owner: 'MetrolistGroup', repo: 'Metrolist', issue_number: 42 };
  const context = {
    repo: { owner: target.owner, repo: target.repo },
    payload: {
      issue: { number: 42, user: { id: 123 } },
      comment: { id: 10, body, user: { id: 123, type: 'User' } },
    },
  };
  const github = { paginate: async (method, args) => {
    assert.equal(method, github.rest.issues.listComments);
    calls.push(['list', args]);
    return comments;
  }, rest: { issues: {
    async createComment(args) {
      calls.push(['record', args]);
      comments.push({ id: 100 + comments.length, body: args.body, user: { id: 41898282, type: 'Bot' } });
    },
    async listComments() {},
    async addLabels(args) { calls.push(['add', args]); },
    async removeLabel(args) { calls.push(['remove', args]); },
    async get(args) { calls.push(['get', args]); return { data: { labels: [] } }; },
  } } };
  return { calls, comments, context, github, target };
}

test('only the human issue author can label issues, never PRs', async () => {
  for (const change of [
    f => { f.context.payload.comment.user.id = 124; },
    f => { f.context.payload.comment.user.type = 'Bot'; },
    f => { f.context.payload.issue.pull_request = {}; },
    f => { delete f.context.payload.issue.user.id; delete f.context.payload.comment.user.id; },
    f => { delete f.context.payload.comment.id; },
  ]) {
    const f = fixture(); change(f);
    await handle(f);
    assert.deepEqual(f.calls, []);
  }
});

test('rejects status/size labels and any additional comment content', async () => {
  for (const body of [
    '/label confirmed', '/unlabel needs-info', '/label size: small', '/label invalid',
    '/label bug\n', '/label bug\n/unlabel fixed-in-git', '/label bug && echo hello',
    'please /label bug', '/label BUG', '/label bug ', '/labels bug',
  ]) {
    const f = fixture(body);
    await handle(f);
    assert.deepEqual(f.calls, [], body);
  }
});

test('accepts exact commands for each public category only', async () => {
  for (const label of ['bug', 'enhancement', 'documentation', 'question', 'preference']) {
    for (const [command, operation, argument] of [
      ['label', 'add', 'labels'], ['unlabel', 'remove', 'name'],
    ]) {
      const f = fixture(`/${command} ${label}`);
      await handle(f);
      assert.deepEqual(f.calls, [
        ['record', { ...f.target, body: `<!-- reporter-labels:v1:10:${command}:${label} -->` }],
        [operation, { ...f.target, [argument]: command === 'label' ? [label] : label }],
        ['list', { ...f.target, per_page: 100 }],
      ]);
    }
  }
});

test('newer author commands supersede older commands for the same label even if jobs start out of order', async () => {
  const f = fixture('/unlabel bug');
  f.context.payload.comment.id = 12;
  f.comments.push(
    { id: 11, body: '/label bug', user: { id: 123, type: 'User' } },
    { id: 12, body: '/unlabel bug', user: { id: 123, type: 'User' } },
  );
  await handle(f);
  f.context.payload.comment = f.comments[0];
  await handle(f);
  assert.deepEqual(f.calls.map(([method]) => method), ['record', 'remove', 'list', 'record', 'add', 'list', 'remove', 'list']);

  for (const other of [
    { body: '/unlabel bug', user: { id: 124, type: 'User' } },
    { body: '/unlabel bug', user: { id: 123, type: 'Bot' } },
    { body: '/label question', user: { id: 123, type: 'User' } },
    { body: '/label confirmed', user: { id: 123, type: 'User' } },
    { body: '/label bug\n', user: { id: 123, type: 'User' } },
    { id: 9, body: '/unlabel bug', user: { id: 123, type: 'User' } },
    { body: '<!-- reporter-labels:v1:11:unlabel:bug -->', user: { id: 123, type: 'User' } },
    { body: '<!-- reporter-labels:v1:11:unlabel:bug -->', user: { id: 456, type: 'Bot' } },
    { body: '<!-- reporter-labels:v1:11:unlabel:confirmed -->', user: { id: 41898282, type: 'Bot' } },
    { body: '<!-- reporter-labels:v1:bogus:unlabel:bug -->', user: { id: 41898282, type: 'Bot' } },
  ]) {
    const g = fixture();
    g.comments.push({ id: 11, ...other });
    await handle(g);
    assert.deepEqual(g.calls.map(([method]) => method), ['record', 'add', 'list']);
  }
});

test('a delayed older write cannot undo a newer command after its comment is edited or deleted', async () => {
  for (const change of [
    (f, latest) => { latest.body = 'edited'; },
    (f, latest) => { f.comments.splice(f.comments.indexOf(latest), 1); },
  ]) {
    const f = fixture('/label bug');
    const latest = { id: 11, body: '/unlabel bug', user: { id: 123, type: 'User' } };
    f.comments.push(f.context.payload.comment, latest);
    let start, release;
    const started = new Promise(resolve => { start = resolve; });
    const waiting = new Promise(resolve => { release = resolve; });
    let present = false;
    f.github.rest.issues.addLabels = async () => { start(); await waiting; present = true; };
    f.github.rest.issues.removeLabel = async () => { present = false; };

    const olderRun = handle(f);
    await started;
    await handle({ github: f.github, context: { ...f.context, payload: { ...f.context.payload, comment: latest } } });
    change(f, latest);
    release();
    await olderRun;
    assert.equal(present, false);
  }
});

test('absent label is idempotent, but other API failures are not ignored', async () => {
  const missing = fixture('/unlabel bug');
  missing.github.rest.issues.removeLabel = async () => { throw { status: 404 }; };
  await handle(missing);
  assert.deepEqual(missing.calls, [
    ['record', { ...missing.target, body: '<!-- reporter-labels:v1:10:unlabel:bug -->' }],
    ['get', missing.target], ['list', { ...missing.target, per_page: 100 }],
  ]);

  for (const setup of [
    f => { f.github.rest.issues.createComment = async () => { throw { status: 403 }; }; },
    f => { f.github.paginate = async () => { throw { status: 403 }; }; },
    f => { f.github.rest.issues.addLabels = async () => { throw { status: 403 }; }; },
    f => { f.context.payload.comment.body = '/unlabel bug'; f.github.rest.issues.removeLabel = async () => { throw { status: 500 }; }; },
    f => { f.context.payload.comment.body = '/unlabel bug'; f.github.rest.issues.removeLabel = async () => { throw { status: 404 }; }; f.github.rest.issues.get = async () => { throw { status: 404 }; }; },
    f => { f.context.payload.comment.body = '/unlabel bug'; f.github.rest.issues.removeLabel = async () => { throw { status: 404 }; }; f.github.rest.issues.get = async () => ({ data: { labels: [{ name: 'bug' }] } }); },
  ]) {
    const f = fixture(); setup(f);
    await assert.rejects(handle(f));
  }
});
