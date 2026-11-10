# Offline injection contract

Before, workload charts had to repeat application settings and include several warmup templates. The proposed contract lets an offline injector read a rendered Deployment and produce reviewable workload and trainer resources.

The contract requires explicit input when rendered YAML cannot identify the launch or safe training mode. It defines stable reinjection, conflict reporting and removal. Helm or the GitOps system applies and removes resources; the injector does not change a live cluster. Cache failures must continue to permit ordinary application startup (Constitution §1–§3).

This feature records the intended contract; it does not implement an injector or verify runtime behavior.
