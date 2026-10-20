# Emula contributor guide

Start with [docs/code-map.md](docs/code-map.md), inspect the relevant symbols and
follow [CONTRIBUTING.md](CONTRIBUTING.md).

Create feature/fix/documentation branches from current `main`; integrate through
pull requests. Do not commit directly to or merge into `main` without explicit
authorization. Preserve other contributors' work and history.

Use JDK 17. Run relevant JVM tests, debug lint/builds, and focused Android checks.
NFC transport/reflection changes also require scoped physical-device evidence.

Keep OEM activation primary. Preserve profiles, firmware-scoped consent, exact
restoration, and per-SET evidence. Resume polling on every exit after a successful
pause; keep primary/rollback errors distinct. UID-only writes change only
`LA_NFCID1`, verify it, and restore its snapshot. Never issue raw SETs just to
inspect hardware or add support claims without device/reader evidence.

Keep keys, private reports, build output, and vendor captures out of Git. Update
the code map when entry points, contracts, or test ownership move.
