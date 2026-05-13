---
name: git-checkpoint
description: Review the current git working tree, identify what should go into .gitignore, propose a sensible commit split, confirm the plan with the user, create the agreed commits, and then ask whether to push. Use when the user wants help organizing and submitting local changes rather than making a blind single commit. Default to Simplified Chinese for user-facing explanations, recommendations, confirmations, and summaries unless the user explicitly requests another language.
---

# Git Checkpoint

Use this skill when the user wants a disciplined git submission workflow for current local changes.

## Language

- Default to Simplified Chinese for user-facing explanations, recommendations, confirmation prompts, and summaries unless the user explicitly requests another language.
- Keep commands, file paths, branch names, commit SHAs, and commit messages in their original form or adapt them only when the user explicitly asks.

## Goals

1. Inspect the current working tree before staging anything.
2. Identify candidate files for `.gitignore` and confirm with the user before editing it.
3. Propose a sensible commit split based on change semantics, not file count.
4. Create only the commits the user explicitly approves.
5. After commits are done, ask separately whether to push.

## Guardrails

- Never modify `.gitignore` without user confirmation.
- Never run `git add`, `git commit`, or `git push` before the user confirms the plan.
- Never use destructive git commands such as `git reset --hard` or `git checkout --` unless the user explicitly asks.
- If staged and unstaged changes are mixed, call that out before proposing the commit plan.
- If sensitive files appear, stop and warn before any commit flow continues.

## Workflow

### 1. Inspect current changes

Start with read-only checks:

```bash
git status --short
git diff --stat
git diff --cached --stat
```

Then inspect specific diffs as needed to understand:

- tracked modifications
- untracked files
- staged vs unstaged state
- likely generated files, caches, logs, IDE files, build outputs, local secrets
- whether multiple unrelated workstreams are mixed together

### 2. Classify `.gitignore` candidates

Group new or changed files into three buckets:

- `suggest_ignore`: generated outputs, logs, caches, IDE files, temporary files, local env files, secrets
- `suggest_commit`: source files, docs, intended assets, intentional config
- `needs_confirmation`: ambiguous files that cannot be safely classified

Report the buckets clearly and ask the user to confirm any `.gitignore` action before editing `.gitignore`.

### 3. Propose commit splits

Propose commit boundaries based on intent. Prefer one clear purpose per commit.

Look for patterns such as:

- infrastructure plus feature adoption
- refactor plus behavior change
- UI copy/style changes mixed with logic changes
- docs bundled with code where coupling may or may not make sense
- unrelated fixes that should not travel together

For each proposed commit, provide:

- goal or theme
- likely file scope
- suggested commit message
- why the split improves reviewability

Then ask the user to confirm:

- whether to accept the split count
- whether to adjust boundaries
- whether to accept or edit the suggested messages

### 4. Execute only after confirmation

Once the user confirms:

- optionally update `.gitignore` if approved
- stage only the agreed files for the current commit
- announce the exact scope before each commit
- create commits in the approved order
- if a file mixes unrelated edits and cannot be safely split, stop and ask the user how to handle it

### 5. Finish with push confirmation

After all commits are created, report:

- commit count
- commit SHAs and summaries
- current branch
- whether the working tree is clean

Then ask explicitly whether to push. Do not push until the user clearly agrees.

## Output structure

Prefer this order:

1. `当前改动概况`
2. `.gitignore 建议`
3. `建议的提交拆分`
4. `等待确认`

## Example confirmations

- `我建议把这些生成文件加入 .gitignore。要我现在修改吗？`
- `我建议把这次改动拆成 2 个提交。要我按这个方案执行吗？`
- `建议的提交信息是：feat: add unified input session for inspection flow。你要保留还是调整？`
- `提交已经完成。要我现在把当前分支推上去吗？`
