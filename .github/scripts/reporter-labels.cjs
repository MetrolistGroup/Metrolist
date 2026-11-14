module.exports = async function handle({ github, context }) {
  const { issue, comment } = context.payload;
  if (issue?.pull_request || !Number.isSafeInteger(issue?.user?.id) ||
      comment?.user?.type !== 'User' || comment.user.id !== issue.user.id) return;

  const match = /^\/(label|unlabel) (bug|enhancement|documentation|question|preference)$/.exec(comment.body);
  if (!match || match[0] !== comment.body) return;

  const label = match[2];
  const target = { ...context.repo, issue_number: issue.number };
  if (!Number.isSafeInteger(comment.id)) return;
  // The bot's marker survives reporter edits/deletions; only the Actions bot can write it.
  await github.rest.issues.createComment({ ...target, body: `<!-- reporter-labels:v1:${comment.id}:${match[1]}:${label} -->` });
  let current = { id: comment.id, command: match[1] };
  const markerPattern = new RegExp(`^<!-- reporter-labels:v1:(\\d+):(label|unlabel):${label} -->$`);
  // shortcut: Scan every issue comment; use a durable queue if issue volume grows.
  while (true) {
    if (current.command === 'label') {
      await github.rest.issues.addLabels({ ...target, labels: [label] });
    } else {
      try {
        await github.rest.issues.removeLabel({ ...target, name: label });
      } catch (error) {
        if (error.status !== 404) throw error;
        // A missing label is harmless; a missing issue or hidden API failure is not.
        const { data } = await github.rest.issues.get(target);
        if (!Array.isArray(data.labels) || data.labels.some(item => item === label || item.name === label)) throw error;
      }
    }

    const comments = await github.paginate(github.rest.issues.listComments, { ...target, per_page: 100 });
    let newer = current;
    for (const other of comments) {
      if (other.user?.id !== 41898282 || other.user.type !== 'Bot') continue;
      const marker = markerPattern.exec(other.body);
      if (marker?.[0] === other.body && Number.isSafeInteger(Number(marker[1])) && Number(marker[1]) > newer.id) {
        newer = { id: Number(marker[1]), command: marker[2] };
      }
    }
    if (newer === current) return;
    current = newer;
  }
};
