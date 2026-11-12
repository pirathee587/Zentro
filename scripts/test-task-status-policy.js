const assert = require('node:assert/strict');
const { taskStatusDecision } = require('./task-status-policy');

const fixtures = [
  ['draft opened', { eventName: 'pull_request', action: 'ready_for_review', draft: true, independentApproval: true, checksPassed: true }, null],
  ['ready without approval', { eventName: 'pull_request', action: 'ready_for_review', independentApproval: false, checksPassed: true }, null],
  ['ready with failed checks', { eventName: 'pull_request', action: 'ready_for_review', independentApproval: true, checksPassed: false }, null],
  ['ready approved and green', { eventName: 'pull_request', action: 'ready_for_review', independentApproval: true, checksPassed: true }, 'in-review'],
  ['approval event after ready', { eventName: 'pull_request_review', action: 'submitted', independentApproval: true, checksPassed: true }, 'in-review'],
  ['check completion after approval', { eventName: 'check_run', action: 'completed', independentApproval: true, checksPassed: true }, 'in-review'],
  ['merge unapproved', { eventName: 'pull_request', action: 'closed', merged: true, independentApproval: false, checksPassed: true }, null],
  ['merge with failed check', { eventName: 'pull_request', action: 'closed', merged: true, independentApproval: true, checksPassed: false }, null],
  ['approved green merge', { eventName: 'pull_request', action: 'closed', merged: true, independentApproval: true, checksPassed: true }, 'done'],
  ['duplicate approved green merge remains same transition', { eventName: 'pull_request', action: 'closed', merged: true, independentApproval: true, checksPassed: true }, 'done']
];

for (const [name, input, expected] of fixtures) {
  assert.equal(taskStatusDecision(input), expected, name);
}
process.stdout.write(`Task status fixtures passed: ${fixtures.length}\n`);
