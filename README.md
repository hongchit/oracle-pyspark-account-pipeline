# Oracle PySpark Account Pipeline

A compact migration demonstration that generates deterministic account data
with Spring Boot and JPA, calculates a legacy Oracle PL/SQL reference result,
reimplements the calculation in PySpark, and reconciles both paths exactly.

The project runs locally in a VS Code Dev Container with Oracle Database Free.
No cloud account or paid service is required.

## Quick start

1. Authenticate Docker with Oracle Container Registry and accept the Database
   Free image terms.
2. Create the ignored local environment file:

   ```bash
   cp .env.example .env
   ```

3. Replace every placeholder with a unique local development value, then open
   the repository in VS Code and choose **Dev Containers: Reopen in Container**.
4. Inside the Dev Container, run the interactive demonstration:

   ```bash
   scripts/run-demo.sh
   ```

The demo pauses after source generation, legacy processing, PySpark output,
and reconciliation. Its default `--cleanup true` replaces existing local demo
rows. Use `scripts/run-demo.sh --help` to view all overrides.

## What it demonstrates

- Spring Boot/JPA source data generation;
- Oracle SQL and a set-based PL/SQL reference batch;
- PySpark JDBC extraction, window deduplication, joins, and exact decimals;
- versioned local Parquet output;
- strict source-to-target reconciliation with JSON and CSV evidence;
- focused Java and Python tests in a reproducible development environment.

## Documentation

- [Architecture](docs/architecture.md)
- [Business rules and data model](docs/business-rules.md)
- [Getting Started](docs/getting-started.md)
- [Outputs and reconciliation](docs/outputs-and-reconciliation.md)
- [Step-by-step workflow demonstration](docs/Workflow%20Demo.md)

## Validation

Run all configured Python and Java style checks inside the Dev Container:

```bash
bash scripts/validate-code.sh
uv run --frozen pytest
```

## License

This project is available under the [MIT License](LICENSE).
