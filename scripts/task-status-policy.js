function taskStatusDecision({ eventName, action, draft, merged, independentApproval, checksPassed }) {
  if (draft) return null;
  if (eventName === 'pull_request' && action === 'closed' && merged) {
    return independentApproval && checksPassed ? 'done' : null;
  }
  const mayReview = (eventName === 'pull_request' && action === 'ready_for_review')
    || eventName === 'pull_request_review'
    || eventName === 'check_run';
  return mayReview && independentApproval && checksPassed ? 'in-review' : null;
}

module.exports = { taskStatusDecision };
