# Archive launch guard

An archive is only useful to a launch that matches the one it was trained for, and the JVM does not
say when it ignores one. Before starting the application, `run` rebuilds the description of the
launch from the java command and compares it with the description stored next to the archive. It
also compares the modification times of the JAR files, because the JVM checks those too. If
everything agrees, the archive flag is added. If anything differs, the application starts exactly
as it was given and the reason is logged.
