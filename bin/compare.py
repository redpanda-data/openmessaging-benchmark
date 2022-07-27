from sh import gnuplot
from sh import mkdir
from sh import rm
import os

import json
from sys import argv

def min_quantiles(data):
    s = []
    for k in data.keys():
        s.append((float(k), data[k]))
    s.sort(key=lambda x:x[0])
    return s[0][1]

def report(name, a_path, b_path):
    def load(dir_path):
        sets = dict()
        for path in os.listdir(dir_path):
            if not os.path.isfile(os.path.join(dir_path, path)):
                continue
            if not path.endswith(".json"):
                continue
            with open(os.path.join(dir_path, path)) as f:
                dataset = json.load(f)
            sets[dataset["workload"] + "-" + dataset["driver"]]=dataset
        return sets
    
    def log(line, ds):
        line += str(min_quantiles(ds["aggregatedPublishLatencyQuantiles"])) + ","
        line += str(ds["aggregatedPublishLatency50pct"]) + ","
        line += str(ds["aggregatedPublishLatency95pct"]) + ","
        line += str(ds["aggregatedPublishLatency99pct"]) + ","
        line += str(ds["aggregatedPublishLatencyMax"]) + ","

        if ds["workload"] != "simple":
            line += str(min_quantiles(ds["aggregatedEndToEndLatencyQuantiles"])) + ","
            line += str(ds["aggregatedEndToEndLatency50pct"]) + ","
            line += str(ds["aggregatedEndToEndLatency95pct"]) + ","
            line += str(ds["aggregatedEndToEndLatency99pct"]) + ","
            line += str(ds["aggregatedEndToEndLatencyMax"]) + ","
        else:
            for i in range(0,5):
                line += ","
        return line
    
    def missing(line):
        for i in range(0,10):
            line += ","
        return line

    asets = load(a_path)
    bsets = load(b_path)

    is_name_used = False

    for akey in asets.keys():
        line = ""
        if is_name_used:
            line = ","
        else:
            line = name + ","
            is_name_used = True
        a = asets[akey]
        line = line + a["workload"] + "," + a["driver"] + ","

        if akey in bsets:
            b = bsets[akey]
            ap50 = a["aggregatedPublishLatency50pct"]
            bp50 = b["aggregatedPublishLatency50pct"]
            dp50 = (100.0 * (bp50 - ap50) / ap50)
            ap99 = a["aggregatedPublishLatency99pct"]
            bp99 = b["aggregatedPublishLatency99pct"]
            dp99 = (100.0 * (bp99 - ap99) / ap99)
            if a["workload"] != "simple":
                ae2e50 = a["aggregatedEndToEndLatency50pct"]
                be2e50 = b["aggregatedEndToEndLatency50pct"]
                de2e50 = (100.0 * (be2e50 - ae2e50) / ae2e50)
                ae2e99 = a["aggregatedEndToEndLatency99pct"]
                be2e99 = b["aggregatedEndToEndLatency99pct"]
                de2e99 = (100.0 * (be2e99 - ae2e99) / ae2e99)
                line = line + f"{dp50},{dp99},{de2e50},{de2e99},"
            else:
                line = line + f"{dp50},{dp99},,,"
        else:
            line = line + ",,,,"

        line = log(line, a)
        if akey in bsets:
            line = log(line, bsets[akey])
        else:
            line = missing(line)
        print(line[:-1])
    
    for bkey in bsets.keys():
        if bkey not in asets:
            line = ""
            if is_name_used:
                line = ","
            else:
                line = name + ","
                is_name_used = True
            b = bsets[bkey]
            line = line + ",,,,"
            line = line + b["workload"] + "," + b["driver"] + "," 
            line = missing(line)
            line = log(line, b)
            print(line[:-1])

if not os.path.isdir(argv[1]):
    raise Exception(f"{argv[1]} isn't a directory")

if len(argv[2:]) != 2:
    raise Exception("Can compare only two versions")

template = dict()
template["groups"] = []

for path in os.listdir(argv[1]):
    if not os.path.isdir(os.path.join(argv[1], path)):
        continue
    path = os.path.join(argv[1], path)
    versions = os.listdir(path)
    if argv[2] not in versions:
        raise Exception(f"can't find {argv[2]} in {path}")
    if argv[3] not in versions:
        raise Exception(f"can't find {argv[3]} in {path}")
    template["groups"].append({
        "name": "todo",
        argv[2]: os.path.join(path, argv[2]),
        argv[3]: os.path.join(path, argv[3])
    })

# header
v0 = argv[2]
v1 = argv[3]
line = f",,,100% * ({v1}-{v0}) / {v0},,,,"
line += v0 + ","
for i in range(0,9):
    line += ","
line += v1 + ","
for i in range(0,8):
    line += ","
print(line)
line = ",,,publish,,end-to-end,,publish,,,,,end-to-end,,,,,publish,,,,,end-to-end,,,,"
print(line)
line = ",,,p50,p99,p50,p99,min,p50,p95,p99,max,min,p50,p95,p99,max,min,p50,p95,p99,max,min,p50,p95,p99,max"
print(line)

for group in template["groups"]:
    report(group["name"], group[v0], group[v1])
    line = ""
    for i in range(0,26):
        line += ","
    # separator
    print(line)