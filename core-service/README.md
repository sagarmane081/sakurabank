# core-service — SakuraBank core banking

Spring Boot 3 / Java 21. Owns accounts, double-entry ledger, transfers,
KYC/AML, audit log, auth (JWT + RBAC).

## Quick Start

### Prerequisites

- Java 21
- Maven
- Docker
- PostgreSQL via Docker

### Run the application

```bash
mvn verify
mvn spring-boot:run