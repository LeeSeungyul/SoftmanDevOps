# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/com/softman/devops/**` hosts CLI bootstrap, HTTP handlers, services, factories, and DTOs; introduce new features inside matching packages.
- Runtime configs belong in `src/main/resources` (e.g., `logback.xml`); ship new assets here so they stay on the classpath.
- Tests mirror the main tree under `src/test/java` with `integration/` suites and reusable stubs in `support/`; place fixtures in `src/test/resources`.
- Gradle outputs live under `build/`; the fat jar publishes to `build/libs/SoftmanDevOps.jar`.

## Build, Test, and Development Commands
```bash
gradle build            # compile, run tests, enforce Jacoco gates
gradle test             # unit + integration tests only
gradle run --args="--port 5050 --logdir logs"  # launch locally
gradle jacocoTestReport # generate coverage report at build/reports/jacoco/test/html
java -jar build/libs/SoftmanDevOps.jar --port 5050  # run packaged jar
```

## Coding Style & Naming Conventions
- Target Java 21, four-space indentation, braces on new lines as in existing classes.
- Favor immutable records for request/response DTOs and `final` classes for utilities.
- Keep package names descriptive (`cli`, `handler`, `service`, `factory`); suffix classes after their role (`*Parser`, `*Handler`).
- Log through SLF4J (`LoggerFactory.getLogger(...)`) and avoid logging sensitive Sonar tokens.

## Testing Guidelines
- JUnit 5 drives tests; suffix classes with `Test` and use descriptive method names (`launchStartsServerAndServesRequests`).
- Integration tests live in `integration/` and rely on `support/SonarStubServer`; grab ports with `TestPorts.findAvailablePort()` to prevent collisions.
- `gradle check` enforces Jacoco thresholds (line ≥ 80%, branch ≥ 70%); publish detailed coverage via `gradle jacocoTestReport`.
- Clean up temp files and stub servers in `@AfterEach` to keep suites deterministic.

## Commit & Pull Request Guidelines
- Existing commits use short, imperative subjects with Conventional Commit prefixes (e.g., `feat: add port validation`, `test: harden retry stub`); mirror this tone.
- Group related changes per commit and reference the impacted module in the scope when it helps (`fix(handler): guard null metrics`).
- Pull requests should explain behaviour, list validation commands (`gradle build`), and link issues; attach CLI or HTTP samples when altering request/response shapes.
- Include log snippets or screenshots whenever log format, concurrency limits, or timeouts change so reviewers can verify impact.

## Security & Configuration Notes
- Route new configuration flags through `CommandLineOptions`, `ConfigurationFactory`, and `ServiceConfiguration` to preserve validation.
- Keep production logging configured via `LoggingInitializer`; never write raw credentials to stdout or logs.
