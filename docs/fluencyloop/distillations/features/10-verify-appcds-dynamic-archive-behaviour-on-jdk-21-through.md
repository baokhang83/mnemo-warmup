# AppCDS dynamic archives are verified on JDK 21 to 24

**Before:** The product promised that a bad startup cache would never stop an application. That promise rested on one JDK (25). JDK 21 to 24 use a different mechanism, the AppCDS archive, and nobody had checked it.

**After:** The promise is measured on JDK 21, 22, 23 and 24. The same two JVM flags train and use the archive on all four releases, so the choice of mechanism per JDK stays as it was.

What the product now knows:

- The JVM ignores a mismatched archive as a whole and starts the application without it. It does not use part of an archive.
- A changed JAR modification time is enough to cause this, even when the JAR content is identical. The training run and the real run must use the same extracted files.
- Some mismatches leave no log line. The product cannot judge "cache used" from the exit code alone.
- The product never asks the JVM to fail on a mismatch. This is now the project's first principle.

The evidence is in `docs/appcds-jdk-matrix.md`. The test can be repeated with `scripts/verify-appcds.sh` when a new JDK appears.
