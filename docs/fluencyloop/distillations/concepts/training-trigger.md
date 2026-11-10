# Training trigger

Many replicas start at once and each sees that no archive exists. Only one should train, and a
failed training must not fail a release. A scheduled job from the chart works out the fingerprint
from the image, checks the store, and stops if the archive is there. If it is missing, the job
takes a lock named after the fingerprint. The lock expires, so a crashed trainer cannot block
others. The job ends successfully even when training fails, and the next run tries again.
