# Training trigger

Many replicas start at once and each sees that no archive exists. Only one should train. The
chart creates a Job whose name contains the fingerprint. Kubernetes allows one Job per name, so a
second attempt is refused and no lock is needed. The Job also checks the store first and stops if
the archive is already there. Pods that start during training run without the archive.
