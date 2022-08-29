from sh import gnuplot
from sh import mkdir
from sh import rm
import os

import json
import jinja2
from sys import argv
from collections import defaultdict

BAR = """
set terminal pngcairo size 1600,1200
set output "{{ output }}.png"
set multiplot

set key left top
set style data histogram
set style histogram cluster gap 1
set style fill solid
set boxwidth 0.9
set xtics format ""
set grid ytics

set yrange [0:{{high_boundary}}]
set lmargin 10
set size 1, 0.5
set origin 0, 0

set notitle
plot "{{ log }}" using 2:xtic(1) title "publish p99", \
     "{{ log }}" using 3 title "e2e p99"

set title "{{title}}"
set yrange [0:{{low_boundary}}]
set size 1, 0.5
set origin 0, 0.5

plot "{{ log }}" using 2:xtic(1) title "publish p99", \
     "{{ log }}" using 3 title "e2e p99"

unset multiplot
"""

DUO = """
set terminal pngcairo size 1600,1200
set output "{{ output }}.png"
set notitle
set multiplot
set lmargin 6
set yrange [0:{{ yrange1 }}]
set xrange [0:{{ xrange }}]

set size 1, 0.5
set origin 0, 0

plot "{{ log1 }}" using 1:2 title "{{ subtitle1 }}" with line lt 1

set title "{{ title }}"
set size 1, 0.5
set origin 0, 0.5
set yrange [0:{{ yrange2 }}]

plot "{{ log2 }}" using 1:2 title "{{ subtitle2 }}" with line lt 2

unset multiplot
"""

SINGLE = """
set terminal pngcairo size 1600,1200
set output "{{ output }}.png"
set title "{{ title }}"
set multiplot
set yrange [0:{{ yrange1 }}]
set xrange [0:{{ xrange }}]

plot "{{ log1 }}" using 1:2 title "{{ subtitle1 }}" with line lt 1

unset multiplot
"""

QUANTILES = """
set terminal pngcairo size 1600,1200
set output "{{ output }}.png"

set title "{{ title }}"
set multiplot
set xrange [0:100]
set logscale y 10

set size 0.7, 1
set origin 0, 0

plot "{{ base.log }}" using 1:2 title "{{ base.name }}" with line{% for measure in rest %},\
     "{{ measure.log }}" using 1:2 title "{{ measure.name }}" with line{% endfor %}

set size 0.3, 1
set origin 0.7, 0
set xrange [99:100.1]

plot "{{ base.log }}" using 1:2 title "{{ base.name }}" with line{% for measure in rest %},\
     "{{ measure.log }}" using 1:2 title "{{ measure.name }}" with line{% endfor %}

"""

LATENCIES_SUB = """
set terminal pngcairo size 1600,1200
set output "{{ output }}.png"
set notitle
set multiplot
set lmargin 6
set yrange [0:{{ yrange_sub }}]
set xrange [0:{{ xrange }}]

set size 1, 0.3
set origin 0, 0

plot "{{ base.log }}" using 1:2 title "{{ base.name }}" with line{% for measure in rest %},\
     "{{ measure.log }}" using 1:2 title "{{ measure.name }}" with line{% endfor %}

set title "{{ title }}"
set size 1, 0.7
set origin 0, 0.3
set yrange [0:{{ yrange_top }}]

plot "{{ base.log }}" using 1:2 title "{{ base.name }}" with line{% for measure in rest %},\
     "{{ measure.log }}" using 1:2 title "{{ measure.name }}" with line{% endfor %}

unset multiplot
"""

class Measures:
    def __init__(self, values, log, name):
        self.values = values
        self.log = log
        self.name = name

def latencies(output, measures, dt, output_name, title, top_cut=0.99, top_padding=1.2, sub_cut=0.3, sub_padding=1.2):
    values = []
    for measure in measures:
        t=0
        with open(f"{output}/{measure.log}", "w") as log:
            for value in measure.values:
                log.write(f"{t}\t{value}\n")
                t+=dt
                values.append(value)
    values.sort()
    boundary_top = values[int(top_cut*len(values))]*top_padding
    if boundary_top == 0.0:
        boundary_top = value[-1]
    if boundary_top == 0.0:
        boundary_top = 1
    boundary_sub = values[int(sub_cut*len(values))]*sub_padding
    if boundary_sub == 0.0:
        boundary_sub = 0.1 * boundary_top
    with open(f"{output}/{output_name}.gnuplot", "w") as output_gnuplot:
        output_gnuplot.write(jinja2.Template(LATENCIES_SUB).render(
            output = output_name,
            title = title,
            yrange_sub = boundary_sub,
            yrange_top = boundary_top,
            xrange = t,
            base = measures[0],
            rest = measures[1:]))
    gnuplot(f"{output_name}.gnuplot", _cwd=output)
    rm(f"{output}/{output_name}.gnuplot")
    for measure in measures:
        rm(f"{output}/{measure.log}")

def single(output, a, dt, output_name, title, cut=0.99, padding=1.2):
    boundary = dict()
    for measure in [a]:
        t=0
        values = []
        with open(f"{output}/{measure.log}", "w") as log:
            for value in measure.values:
                log.write(f"{t}\t{value}\n")
                t+=dt
                values.append(value)
        values.sort()
        boundary[measure.log] = values[int(cut*len(values))]*padding
    with open(f"{output}/{output_name}.gnuplot", "w") as output_gnuplot:
        output_gnuplot.write(jinja2.Template(SINGLE).render(
            output = output_name,
            title = title,
            yrange1 = boundary[a.log],
            xrange = t,
            log1 = a.log,
            subtitle1 = a.name))
    gnuplot(f"{output_name}.gnuplot", _cwd=output)
    rm(f"{output}/{output_name}.gnuplot")
    for measure in [a]:
        rm(f"{output}/{measure.log}")

def duo(output, a, b, dt, output_name, title, cut=0.99, padding=1.2):
    boundary = dict()
    for measure in [a, b]:
        t=0
        values = []
        with open(f"{output}/{measure.log}", "w") as log:
            for value in measure.values:
                log.write(f"{t}\t{value}\n")
                t+=dt
                values.append(value)
        values.sort()
        boundary[measure.log] = values[int(cut*len(values))]*padding
    with open(f"{output}/{output_name}.gnuplot", "w") as output_gnuplot:
        output_gnuplot.write(jinja2.Template(DUO).render(
            output = output_name,
            title = title,
            yrange1 = boundary[a.log],
            yrange2 = boundary[b.log],
            xrange = t,
            log1 = a.log,
            subtitle1 = a.name,
            log2 = b.log,
            subtitle2 = b.name))
    gnuplot(f"{output_name}.gnuplot", _cwd=output)
    rm(f"{output}/{output_name}.gnuplot")
    for measure in [a, b]:
        rm(f"{output}/{measure.log}")

def quantiles(output, measures, output_name, title):
    for measure in measures:
        dataset = []
        for key in measure.values.keys():
            dataset.append((float(key), measure.values[key]))
        dataset.sort(key=lambda x:x[0])
        with open(f"{output}/{measure.log}", "w") as log:
            for p,v in dataset:
                log.write(f"{p}\t{v}\n")
    with open(f"{output}/{output_name}.gnuplot", "w") as output_gnuplot:
        output_gnuplot.write(jinja2.Template(QUANTILES).render(
            output = output_name,
            title = title,
            base = measures[0],
            rest = measures[1:]))
    gnuplot(f"{output_name}.gnuplot", _cwd=output)
    rm(f"{output}/{output_name}.gnuplot")
    for measure in measures:
        rm(f"{output}/{measure.log}")

output="smoke/report"


def analyse(output, data, dt, prefix=""):
    mkdir("-p", output)

    latencies(
        output,
        [
            Measures(values=data["publishLatencyMin"], log="pub-min.log", name="min"),
            Measures(values=data["publishLatency50pct"], log="pub-p50.log", name="p50"),
            Measures(values=data["publishLatency75pct"], log="pub-p75.log", name="p75"),
            Measures(values=data["publishLatency95pct"], log="pub-p95.log", name="p95"),
            Measures(values=data["publishLatency99pct"], log="pub-p99.log", name="p99"),
            Measures(values=data["publishLatencyMax"], log="pub-max.log", name="max")
        ],
        dt=dt,
        output_name="pub",
        title=prefix + f"publish (ms, {int(dt)}s agg)"
    )

    latencies(
        output,
        [
            Measures(values=data["publishDelayLatency50pct"], log="delay-p50.log", name="p50"),
            Measures(values=data["publishDelayLatency75pct"], log="delay-p75.log", name="p75"),
            Measures(values=data["publishDelayLatency95pct"], log="delay-p95.log", name="p95"),
            Measures(values=data["publishDelayLatency99pct"], log="delay-p99.log", name="p99"),
            Measures(values=data["publishDelayLatencyMax"], log="delay-max.log", name="max")
        ],
        dt=dt,
        output_name="delay",
        title=prefix + f"publish delay (ms, {int(dt)}s agg)"
    )

    latencies(
        output,
        [
            Measures(values=data["scheduleLatencyMax"], log="async-max.log", name="max")
        ],
        dt=dt,
        output_name="async",
        title=prefix + f"async send (ms, {int(dt)}s agg)"
    )

    if data["workload"] != "simple":
        latencies(
            output,
            [
                Measures(values=data["endToEndLatencyMin"], log="e2e-min.log", name="min"),
                Measures(values=data["endToEndLatency50pct"], log="e2e-p50.log", name="p50"),
                Measures(values=data["endToEndLatency75pct"], log="e2e-p75.log", name="p75"),
                Measures(values=data["endToEndLatency95pct"], log="e2e-p95.log", name="p95"),
                Measures(values=data["endToEndLatency99pct"], log="e2e-p99.log", name="p99"),
                Measures(values=data["endToEndLatencyMax"], log="e2e-max.log", name="max")
            ],
            dt=dt,
            output_name="e2e",
            title=prefix + f"end-to-end (ms, {int(dt)}s agg)"
        )

        duo(
            output,
            Measures(values=data["sent"], log="sent.log", name="sent"),
            Measures(values=data["consumed"], log="consumed.log", name="consumed"),
            dt=dt,
            output_name="throughput",
            title=prefix + f"throughput (ops, {int(dt)}s agg)"
        )

        quantiles(
            output, 
            [
                Measures(values=data["aggregatedPublishLatencyQuantiles"], log="p-p.log", name="publish p-values"),
                Measures(values=data["aggregatedEndToEndLatencyQuantiles"], log="p-e2e.log", name="end-to-end p-values")
            ],
            "quantiles",
            prefix+"quantiles"
        )
    else:
        single(
            output,
            Measures(values=data["sent"], log="sent.log", name="sent"),
            dt=dt,
            output_name="throughput",
            title=prefix + f"throughput (ops, {int(dt)}s agg)"
        )

        quantiles(
            output, 
            [
                Measures(values=data["aggregatedPublishLatencyQuantiles"], log="p-p.log", name="publish p-values")
            ],
            "quantiles",
            prefix+"quantiles"
        )

def bar(output, datasets, output_name, title):
    datasets.sort(key=lambda x:x["publishRate"])
    values = []
    with open(f"{output}/{output_name}.log", "w") as output_log:
        for dataset in datasets:
            values.append(dataset["aggregatedPublishLatency99pct"])
            values.append(dataset["aggregatedEndToEndLatency99pct"])
            output_log.write(dataset["workload"][5:] + "\t" + str(dataset["aggregatedPublishLatency99pct"]) + "\t" + str(dataset["aggregatedEndToEndLatency99pct"]) + "\n")
    values.sort()
    low_boundary = values[int(len(values)*0.65)]*1.2
    high_boundary = values[-1]*1.2
    
    with open(f"{output}/{output_name}.gnuplot", "w") as output_gnuplot:
        output_gnuplot.write(jinja2.Template(BAR).render(
            output = output_name,
            title = title,
            high_boundary = high_boundary,
            low_boundary = low_boundary,
            log = f"{output_name}.log"))
    gnuplot(f"{output_name}.gnuplot", _cwd=output)
    rm(f"{output}/{output_name}.gnuplot")
    rm(f"{output}/{output_name}.log")

    
def report(dir_path, version):
    trend = defaultdict(lambda: [])
    for path in os.listdir(dir_path):
        # check if current path is a file
        if not os.path.isfile(os.path.join(dir_path, path)):
            continue
        if not path.endswith(".json"):
            continue
        with open(os.path.join(dir_path, path)) as f:
            dataset = json.load(f)
        if dataset["workload"].startswith("load."):
            trend[dataset["driver"]].append(dataset)
        rate = dataset["sampleRateMillis"] / 1000
        analyse(os.path.join(dir_path, "report", dataset["workload"] + "-" + dataset["driver"]), dataset, rate, f"{version} " + dataset["workload"] + " " + dataset["driver"] + " ")
    for key in trend.keys():
        if len(trend[key]) == 1:
            continue
        bar(os.path.join(dir_path, "report"), trend[key], f"load {key}", f"{version} {key}")
        

report(argv[1], argv[2])