# AGENTS.md

## Commit & Push Rules

- **Always commit and push** after finishing work (unless the user says otherwise).
- **Never commit `.md` files** (PRODUCT.md, DESIGN.md, and other agent-generated docs — they are gitignored).
  - Exception (G1/Q6): `rust/**/*.md` may be committed — DECISIONS, MAP, GLOSSARY, HANDOFF, RULES and generated crate READMEs must survive a fresh clone.
- **Never commit skill/plugin files or folders** (`.impeccable/`, `.opencode/`, `.claude/` skill artifacts).
- **Never build locally** — the user builds on GitHub Actions. Do not run `./gradlew` builds, assemble, or install; code changes must compile on CI.
