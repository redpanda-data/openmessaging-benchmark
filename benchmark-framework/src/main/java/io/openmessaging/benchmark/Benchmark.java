/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.openmessaging.benchmark;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.beust.jcommander.converters.FileConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.common.base.Throwables;

import io.openmessaging.benchmark.worker.DistributedWorkersEnsemble;
import io.openmessaging.benchmark.worker.SwarmWorker;
import io.openmessaging.benchmark.worker.LocalWorker;
import io.openmessaging.benchmark.worker.Worker;

public class Benchmark {
    static enum Topology {
        SWARM("swarm"),
        ENSEMBLE("ensemble");

        public final String name;

        Topology(String name) {
            this.name = name;
        }
    }

    static class Arguments {

        @Parameter(names = {"-c", "--csv"}, description = "Print results from this directory to a csv file",
                converter = FileConverter.class)
        File resultsDir;

        @Parameter(names = { "-h", "--help" }, description = "Help message", help = true)
        boolean help;

        @Parameter(names = { "-d",
                "--drivers" }, description = "Drivers list. eg.: pulsar/pulsar.yaml,kafka/kafka.yaml")//, required = true)
        public List<String> drivers;

        @Parameter(names = { "-t", "--topology" }, description = "Workers topology. eg: swarm or ensemble")
        public String topology;

        @Parameter(names = { "-w",
                "--workers" }, description = "List of worker nodes. eg: http://1.2.3.4:8080,http://4.5.6.7:8080")
        public List<String> workers;

        @Parameter(names = { "-wf",
                "--workers-file" }, description = "Path to a YAML file containing the list of workers addresses")
        public File workersFile;

        @Parameter(names = { "-x", "--extra" }, description = "Allocate extra consumer workers when your backlog builds.")
        boolean extraConsumers;

        @Parameter(description = "Workloads")//, required = true)
        public List<String> workloads;

        @Parameter(names = { "-o", "--output" }, description = "Output", required = false)
        public String output;

        @Parameter(names = { "-v", "--service-version" }, description = "Optional version of the service being benchmarked, embedded in the final result", required = false)
        public String serviceVersion;
    }

    /**
     * Load and validate workload.
     */
    private static Workload readWorkload(File file) throws IOException {
        Workload w = mapper.readValue(file, Workload.class);
        w.validate();
        return w;
    }

    public static void main(String[] args) throws Exception {
        final Arguments arguments = new Arguments();
        JCommander jc = new JCommander(arguments);
        jc.setProgramName("messaging-benchmark");

        try {
            jc.parse(args);
        } catch (ParameterException e) {
            System.err.println(e.getMessage());
            jc.usage();
            System.exit(-1);
        }

        if (arguments.help) {
            jc.usage();
            System.exit(-1);
        }

        if(arguments.resultsDir != null) {
            ResultsToCsv r = new ResultsToCsv();
            r.writeAllResultFiles(arguments.resultsDir);
            System.exit(0);
        } else if (arguments.drivers == null)  {
            System.err.println("--drivers must be specified");
            System.exit(-1);
        } else if (arguments.workloads == null) {
            System.err.println("At least one workload must be specified");
            System.exit(-1);
        }

        if (arguments.workers != null && arguments.workersFile != null) {
            System.err.println("Only one between --workers and --workers-file can be specified");
            System.exit(-1);
        }

        if (arguments.workers == null && arguments.workersFile == null) {
            File defaultFile = new File("workers.yaml");
            if (defaultFile.exists()) {
                log.info("Using default worker file workers.yaml");
                arguments.workersFile = defaultFile;
            }
        }

        if (arguments.workersFile != null) {
            log.info("Reading workers list from {}", arguments.workersFile);
            arguments.workers = mapper.readValue(arguments.workersFile, Workers.class).workers;
        }

        // Dump configuration variables
        log.info("Starting benchmark with config: {}", writer.writeValueAsString(arguments));

        Map<String, Workload> workloads = new TreeMap<>();
        for (String path : arguments.workloads) {
            File file = new File(path);
            String name = file.getName().substring(0, file.getName().lastIndexOf('.'));

            workloads.put(name, readWorkload(file));
        }

        log.info("Workloads: {}", writer.writeValueAsString(workloads));

        Worker worker;

        if (arguments.workers != null && !arguments.workers.isEmpty()) {
            if (arguments.topology == null || arguments.topology.equals(Topology.ENSEMBLE.name)) {
                log.info("Using DistributedWorkersEnsemble workers topology");
                worker = new DistributedWorkersEnsemble(arguments.workers, arguments.extraConsumers);
            } else if (arguments.topology.equals(Topology.SWARM.name)) {
                log.info("Using SwarmWorker workers topology");
                worker = new SwarmWorker(arguments.workers);
            } else {
                log.error("Unsupported wroker topology: {}", arguments.topology);
                throw new RuntimeException();
            }
        } else {
            // Use local worker implementation
            worker = new LocalWorker();
        }

        boolean success = false;
        try {
            workloads.forEach((workloadName, workload) -> {
                arguments.drivers.forEach(driverConfig -> {
                    try {
                        String beginTime = dateFormat.format(new Date());
                        File driverConfigFile = new File(driverConfig);
                        DriverConfiguration driverConfiguration = tolerantMapper.readValue(driverConfigFile,
                                DriverConfiguration.class);
                        log.info("--------------- WORKLOAD : {} --- DRIVER : {}---------------", workload.name,
                                driverConfiguration.name);

                        // Stop any left over workload
                        worker.stopAll();

                        worker.initializeDriver(new File(driverConfig));

                        WorkloadGenerator generator = new WorkloadGenerator(driverConfiguration.name, workload, worker);

                        TestResult result = generator.run();
                        result.beginTime = beginTime;
                        result.endTime = dateFormat.format(new Date());
                        result.version = arguments.serviceVersion;

                        boolean useOutput = (arguments.output != null) && (arguments.output.length() > 0);

                        String fileName = useOutput? arguments.output: String.format("%s-%s-%s.json", workloadName,
                        driverConfiguration.name, dateFormat.format(new Date()));

                        log.info("Writing test result into {}", fileName);
                        writer.writeValue(new File(fileName), result);

                        generator.close();
                    } catch (Exception e) {
                        log.error("Failed to run the workload '{}' for driver '{}'", workload.name, driverConfig, e);
                        Throwables.throwIfUnchecked(e);
                        throw new RuntimeException(e);
                    } finally {
                        try {
                            worker.stopAll();
                        } catch (IOException e) {
                        }
                    }
                });
            });
            success = true;
        } catch (Exception e) {
            // we already caught & logged the exception above inside the forEach
            // so we catch here so we can explicitly exit below
        } finally {
            worker.close();
        }

        // We catch and eat exceptions thrown by benchmark runs above, but we would like the process
        // to return a non-zero exit code on failure, for the benefit of the caller.
        System.exit(success ? 0 : 1);
    }

    private static final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    // jackson mapper which allows unknown properties, which we need for driver config, since
    // drivers config has arbitrary driver-specific properties
    private static final ObjectMapper tolerantMapper = new ObjectMapper(new YAMLFactory())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");

    private static final Logger log = LoggerFactory.getLogger(Benchmark.class);
}
