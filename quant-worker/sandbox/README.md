# Linux strategy container

Build the smoke-test image on a Linux worker with Docker:

```sh
image=$(docker build --quiet -f quant-worker/sandbox/Dockerfile .)
QUANT_HOST_SECRET=smoke-probe mvn -B -ntp -pl quant-worker -am -Dquant.sandbox.image="$image" verify
```

For a job, a trusted provisioner prepares an immutable, approved image containing the SDK, launcher and the strategy package under `/opt/strategy`. Use the locally available content ID (returned by `docker image inspect --format '{{.Id}}' IMAGE`). Keep image builds and dependencies separate from the execution host. The launcher runs against the local Linux Docker daemon and receives its executable path and image ID from trusted configuration. It never pulls an image, mounts a host path, exposes the Docker socket to the container, or forwards the host environment. A strategy selects an importable callable in the approved image.

The worker runs each strategy in its own non-root container with no external network, a read-only root, a bounded scratch filesystem, no capabilities, no privilege escalation, and CPU, memory, PID and file-descriptor limits. The subprocess uses a per-exchange deadline. A host timer removes the container at the job deadline even during idle periods. The in-container deadline also terminates PID 1 and the process namespace when the job outlives the Java launcher. The worker waits a bounded time for Docker control commands and reports cleanup failures to the caller.

The Docker daemon and image provisioner are privileged components on the worker host. Run them on dedicated worker infrastructure with maintained kernel, default Docker seccomp profile and no API control-plane credentials. The approved image must include the launcher and GNU timeout. The daemon and worker OS are trusted, and a shared-kernel container provides a lower isolation boundary than a dedicated VM. If a worker or daemon crashes, an operator or worker host reaper must clean containers labeled `io.github.quant.strategy-sandbox=true`. Image creation and authorization for tenant submissions are external to this module.

The Linux smoke test replays a strategy through Java and checks effective user, capability mask, no-new-privileges, read-only filesystem, writable temporary storage, and external-network denial. It also probes for Docker socket and host environment exposure.
