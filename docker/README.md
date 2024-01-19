# OpenMessaging Benchmark Framework Docker

Builds a Docker image of OpenMessaging Benchmark using the included `Dockerfile`.

This isn't hooked into the default lifecycle, so requires explicitly passing the goal to Maven.

```sh
mvn docker:build
```

If running from the parent directory:

```sh
mvn clean install
mvn docker:build -pl docker
```

A resulting docker image will be built and tagged:

```
$ docker images 'docker.redpanda.com/redpandadata/openmessaging-benchmark'
REPOSITORY                                                 TAG              IMAGE ID       CREATED         SIZE
docker.redpanda.com/redpandadata/openmessaging-benchmark   0.0.1-SNAPSHOT   7bdf2de3e52e   5 minutes ago   985MB
docker.redpanda.com/redpandadata/openmessaging-benchmark   latest           7bdf2de3e52e   5 minutes ago   985MB
```