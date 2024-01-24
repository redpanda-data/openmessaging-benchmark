# OpenMessaging Benchmark Framework Helm Chart

## Packaging

To create a tarball of the Chart, you may need `helm` available in your current `$PATH`.

If running from this module's directory:

```sh
$ mvn helm:package
```

If running from the parent project directory:

```sh
mvn helm:package -pl deployment
```

## Using the Helm Chart to run a Benchmark

1. Make sure you've built, tagged, and pushed the Docker image. See the
   [README.md](../docker/README.md) for how to build using Maven.
2. Create and customize a `values.yaml` file based on the Chart's
   [values.yaml](./kubernetes/helm/benchmark/values.yaml).
3. Use `helm install` to deploy the chart. Once running, you should see
   your driver pod _Running_ in your namespace.
4. Use `kubectl attach -it -n <namespace> omb-driver` to connect to the
   running shell interpreter.
5. Once connected, run `./run.sh` to begin a test run.
6. Output will appear as json files in `/rum/omb/` backed by a PV.