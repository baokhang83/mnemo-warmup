# Trainer CronJob

The archive is made in the real cluster by a scheduled job that runs the application's own image, because
that is where the JDK, the JAR files and the configuration the application really uses are. Each run is
safe to repeat: it first checks whether the archive exists, takes the lease, trains if needed, uploads, and
reports. It is not part of a release or of pod start-up, so a slow or failing run can only delay the first
archive, never an application.
