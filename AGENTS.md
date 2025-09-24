# Repository Guidelines

## Project Structure & Module Organization
Keep production code under `src/main/java/com/softman/devops/**`, grouping classes by role (`cli`, `handler`, `service`, `factory`, `dto`). Runtime resources such as `logback.xml` live in `src/main/resources` so they travel with the jar. Mirror the tree in `src/test/java`, placing HTTP stubs under `support/` and integration specs in `integration/`. Fixtures belong in `src/test/resources`. Build artefacts are generated in `build/`, with the runnable jar emitted at `build/libs/SoftmanDevOps.jar`.

## Build, Test, and Development Commands
- `gradle build` — compiles, runs every test suite, and enforces Jacoco quality gates.
- `gradle test` — executes unit and integration tests without packaging.
- `gradle run --args="--port 5050 --logdir logs"` — launches the CLI locally with explicit runtime options.
- `gradle jacocoTestReport` — produces HTML coverage at `build/reports/jacoco/test/html/index.html`.
- `java -jar build/libs/SoftmanDevOps.jar --port 5050` — validates the shaded jar in the same way production does.

## Coding Style & Naming Conventions
Target Java 21 with four-space indentation and braces on their own lines, matching existing classes. Prefer records for immutable DTOs and mark helper utilities as `final`. Use descriptive package names and suffix classes with their responsibility (`SonarMetricsHandler`, `CommandLineParser`). Log exclusively through SLF4J (`LoggerFactory.getLogger(...)`) and omit sensitive tokens from output.

## Testing Guidelines
Write JUnit 5 tests with descriptive method names such as `launchStartsServerAndServesRequests`. Integration tests should acquire ports via `TestPorts.findAvailablePort()` and tear down `SonarStubServer` instances in `@AfterEach`. Maintain coverage thresholds by running `gradle check`; branch coverage must stay ≥70% and line coverage ≥80%. When adding fixtures or stub payloads, keep them small and store them beside the suite in `src/test/resources`.

## Commit & Pull Request Guidelines
Follow Conventional Commit subjects (`feat:`, `fix(handler):`, `test:`) written in the imperative and scoped when helpful. Consolidate related work per commit and explain behavioural changes in the body. Pull requests need a concise summary, linked issue or ticket, and the validation commands you ran (e.g., `gradle build`). Include sample CLI invocations or response snippets when interfaces change to help reviewers verify impact.

## Security & Configuration Notes
Thread new configuration flags through `CommandLineOptions`, `ConfigurationFactory`, and `ServiceConfiguration` so validation stays centralized. Keep logging wired via `LoggingInitializer` and never echo credentials or API tokens to stdout or the logs. Prefer environment variables or secure stores for secrets referenced during development.
