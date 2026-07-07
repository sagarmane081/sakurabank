# SakuraBank（サクラ銀行）

<!-- Replace OWNER/REPO after pushing -->
![CI](https://github.com/OWNER/sakurabank/actions/workflows/ci.yml/badge.svg)

A full-stack, enterprise-grade digital banking application: double-entry ledger,
KYC/AML compliance engine, immutable audit log, and a Japanese-language RAG
chatbot powered by the Claude API — built with a test-first workflow, security
scanning, and CI/CD from the first commit.

> 🚧 **Status: Phase 0 — skeleton.** Live demo link, demo credentials, and
> architecture diagram land here as phases complete (target: 2026-07-31).

## Quick start (local)

```bash
docker compose up --build
```

| Service | URL | Health |
|---|---|---|
| core-service (Spring Boot 3 / Java 21) | http://localhost:8080 | `/actuator/health` |
| ai-service (FastAPI / Python 3.12) | http://localhost:8000 | `/health` |
| PostgreSQL 16 + pgvector | localhost:5432 | — |
| Redis 7 | localhost:6379 | — |

## Repository structure

```
sakurabank/
├── core-service/        # Spring Boot — accounts, ledger, transfers, KYC/AML, audit, auth
├── ai-service/          # FastAPI — Japanese RAG chatbot (Claude API), eval harness
├── frontend/            # React 18 + TypeScript + Vite (Phase 4)
├── infra/               # CloudFormation, DB init, deploy scripts
├── docs/adr/            # Architecture Decision Records
└── .github/workflows/   # CI: lint → build → tests → coverage gates → gitleaks
```

## Engineering conventions

- **Test-first on all domain logic**; 80% service-layer coverage gates enforced in CI (JaCoCo / pytest-cov)
- **Zero secrets in the repo** — env vars locally, AWS SSM in production; gitleaks runs on every push
- Money is `BigDecimal` / integer minor units only — no floating point anywhere near currency
- Conventional commits; every ADR in `docs/adr/`

---

## 日本語概要

サクラ銀行は、日本のエンタープライズ/フィンテック企業が求める実務能力を実証する
ためのフルスタック・デジタルバンキングアプリです。複式簿記による勘定元帳、
KYC/AMLコンプライアンスエンジン、改ざん不可能な監査ログ、そしてClaude APIを
活用した日本語RAGチャットボットを備えています。テストファースト開発、
セキュリティスキャン、CI/CDを初回コミットから導入しています。

（ライブデモのURL・デモ用アカウントは2026年7月末に公開予定）
