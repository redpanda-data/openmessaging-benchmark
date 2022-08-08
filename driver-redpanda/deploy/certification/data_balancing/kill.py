import re
from threading import Thread
import json
from sh import benchmark
from sh import ssh
import requests
from time import sleep
from sys import argv
import sys
import traceback

brokers_title = re.compile("ID\\s+HOST\\s+PORT")
broker = re.compile("(\\d+)\\*{0,1}\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+\\s+\\d+")
topics_title = re.compile("NAME\\s+PARTITIONS\\s+REPLICAS")
topic = re.compile("([^\\s]+)\\s+\\d+\\s+\\d+")
result = re.compile(".+Writing test result into (.+)$")

class Broker:
    def __init__(self, id, host):
        self.id = id
        self.host = host

class Metadata:
    def __init__(self):
        self.brokers = []
        self.topics = []
    
    def parse(self, rpk_metadata):
        self.brokers = []
        self.topics = []
        lines = list(map(lambda x: x.strip(), rpk_metadata.split("\n")))

        i = 0
        while i < len(lines):
            if lines[i].startswith("TOPICS"):
                if not lines[i+1].startswith("======"):
                    raise Exception(f"can't parse topics in {rpk_metadata}")
                if not topics_title.match(lines[i+2]):
                    raise Exception(f"can't parse topics in {rpk_metadata}")
                i+=3
                while i < len(lines) and len(lines[i]) != 0:
                    m = topic.match(lines[i])
                    if not m:
                        raise Exception(f"can't parse topics in {rpk_metadata}")
                    name = m.group(1)
                    self.topics.append(name)
                    i+=1
                continue
            if lines[i].startswith("BROKERS"):
                if not lines[i+1].startswith("======="):
                    raise Exception(f"can't parse brokers in {rpk_metadata}")
                if not brokers_title.match(lines[i+2]):
                    raise Exception(f"can't parse brokers in {rpk_metadata}")
                i+=3
                while i < len(lines) and len(lines[i]) != 0:
                    m = broker.match(lines[i])
                    if not m:
                        raise Exception(f"can't parse brokers in {rpk_metadata}")
                    id = int(m.group(1))
                    host = m.group(2)
                    self.brokers.append(Broker(id, host))
                    i+=1
                continue
            i+=1

has_error = False

def fault():
    global has_error
    try:
        sleep(20*60)
        node = None
        with open("/opt/benchmark/redpanda", "r") as f:
            for line in f:
                node = line.strip()
                break
        info = ssh("-i", argv[4], f"{argv[3]}@{node}", "rpk", "cluster", "metadata")
        meta = Metadata()
        meta.parse(info)
        topic = [x for x in meta.topics if x != "__consumer_offsets"][0]
        ip = meta.brokers[0].host
        r = requests.get(f"http://{ip}:9644/v1/partitions/kafka/{topic}/0")
        if r.status_code != 200:
            raise Exception(f"can't get partition info for {topic}")
        info = r.json()
        leader = info["leader_id"]
        follower = None
        for replica in info["replicas"]:
            if replica["node_id"] != info["leader_id"]:
                follower = replica["node_id"]
        print(f"[driver] topic: {topic}")
        print(f"[driver] leader: {leader}")
        print(f"[driver] follower: {follower}")
        
        for broker in meta.brokers:
            if broker.id == follower:
                ip = broker.host
        print(f"[driver] killing {ip}")
        ssh("-i", argv[4], f"{argv[3]}@{node}", "sudo", "rpk", "redpanda", "stop")
        print("[driver] killed")
    except:
        has_error = True
        e, v = sys.exc_info()[:2]
        trace = traceback.format_exc()
        print(e)
        print(v)
        print(trace)
        print("[driver] ERROR!")
        raise

fault_thread = None
result_file = None

for line in benchmark("-t", "swarm", "-d", argv[1], argv[2], _err_to_out = True, _iter=True):
    print(line)
    if "Starting warm-up traffic" in line:
        pass
    elif "Starting benchmark traffic" in line:
        pass
        fault_thread = Thread(target=fault)
        fault_thread.start()
    elif "Writing test result into" in line:
        result_file = result.match(line).group(1)


print("[driver] done")

if fault_thread != None:
    fault_thread.join()

if result_file != None:
    data = None
    with open(result_file, "r") as f:
        data = json.load(f)
    data["trustworthy"] = not has_error
    with open(result_file, "w") as f:
        json.dump(data, f)