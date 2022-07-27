from sh import gnuplot
from sh import mkdir
from sh import rm
import os

import json
import jinja2
from sys import argv
from collections import defaultdict

def min_quantiles(data):
    s = []
    for k in data.keys():
        s.append((float(k), data[k]))
    s.sort(key=lambda x:x[0])
    return s[0][1]

def report(a_path, b_path):
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

    for akey in asets.keys():
        a = asets[akey]
        line = a["workload"] + "," + a["driver"] + "," 
        line = log(line, a)
        if akey in bsets:
            line = log(line, bsets[akey])
        else:
            line = missing(line)
        print(line[:-1])
    
    for bkey in bsets.keys():
        if bkey not in asets:
            b = bsets[bkey]
            line = b["workload"] + "," + b["driver"] + "," 
            line = missing(line)
            line = log(line, b)
            print(line[:-1])

report(argv[1], argv[2])