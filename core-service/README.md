# core-service — SakuraBank core banking

Spring Boot 3 / Java 21. Owns accounts, double-entry ledger, transfers,
KYC/AML, audit log, auth (JWT + refresh rotation, RBAC).

```bash
mvn verify        # unit tests + JaCoCo coverage gate
mvn spring-boot:run
```

Generate the Maven wrapper once locally (not vendored):

```bash
mvn wrapper:wrapper
```
