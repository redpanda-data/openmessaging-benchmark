#!/usr/bin/env python3
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

import json
import sys
import statistics
import numpy as np
import argparse
import re
import pygal
from pygal.style import Style
from itertools import chain
from os import walk
from os import path

chartStyle = Style(
    background='transparent',
    plot_background='transparent',
    font_family='googlefont:Montserrat',
    # colors=('#D8365D', '#78365D')
    colors=('#66CC69', '#173361', '#D8365D'),
    # colors=('#66CC69', '#667C69', '#173361', '#D8365D', '#78365D'),
)

#theme = pygal.style.CleanStyle
theme = chartStyle
fill = False


def create_quantile_chart(workload, title, y_label, time_series):
    import math
    chart = pygal.XY(style=theme, dots_size=0.5,
                     legend_at_bottom=True,
                     truncate_legend=37,
                     x_value_formatter=lambda x: '{:,.2f} %'.format(
                         100.0 - (100.0 / (10**x))),
                     show_dots=False, fill=fill,
                     stroke_style={'width': 2},
                     show_y_guides=False, show_x_guides=True)
    chart.title = title
    # chart.stroke = False

    chart.human_readable = True
    chart.y_title = y_label
    chart.x_title = 'Percentile'
    chart.x_labels = [0.30103, 1, 2, 3]

    for label, values, opts in time_series:
        values = sorted((float(x), y) for x, y in values.items())
        xy_values = [(math.log10(100 / (100 - x)), y)
                     for x, y in values if x <= 99.9]
        chart.add(label, xy_values, stroke_style=opts)

    chart.render_to_file('%s.svg' % workload)


def create_multi_chart(svg_file_name, title, y_label_1, y_label_2, time_series):
    chart = pygal.XY(style=theme, dots_size=1, show_dots=False, stroke_style={'width': 2}, fill=fill,
                     legend_at_bottom=True, show_x_guides=False, show_y_guides=False)
    chart.title = title
    chart.human_readable = True
    chart.x_title = 'Time (seconds)'

    ys_1 = []
    ys_2 = []

    for label_1, values_1, label_2, values_2 in time_series:
        ys_1.append(values_1)
        chart.add(label_1, [(10*x, y) for x, y in enumerate(values_1)])
        chart.add(label_2, [(10*x, y)
                            for x, y in enumerate(values_2)], secondary=True)

    ys_1 = chain.from_iterable(ys_1)
    ys_2 = chain.from_iterable(ys_2)
    max_y_1 = float('-inf')  # Hack for starting w/ INT64_MIN
    max_y_2 = max_y_1
    for y in ys_1:
        if max_y_1 < y:
            max_y_1 = y
    chart.range = (0, max_y_1 * 1.20)
    chart.render_to_file('%s.svg' % svg_file_name)


def create_bar_chart(workload, title, y_label, x_label, data):
    chart = pygal.Bar(
        style=theme, dots_size=1, show_dots=False, stroke_style={'width': 2}, fill=fill,
        show_legend=False, show_x_guides=False, show_y_guides=True
    )
    chart.title = title
    chart.x_labels = x_label
    chart.value_formatter = lambda y: "{:,.0f}".format(y)

    for label, points in data.items():
        chart.add(label, points)
    chart.render_to_file('%s.svg' % workload)


def create_chart(workload, title, y_label, time_series):
    chart = pygal.XY(style=theme, dots_size=1, show_dots=False, stroke_style={'width': 2}, fill=fill,
                     legend_at_bottom=True, show_x_guides=False, show_y_guides=False)
    chart.title = title

    chart.human_readable = True
    chart.y_title = y_label
    chart.x_title = 'Time (seconds)'
    # line_chart.x_labels = [str(10 * x) for x in range(len(time_series[0][1]))]

    ys = []
    for label, values in time_series:
        ys.append(values)
        chart.add(label, [(10*x, y) for x, y in enumerate(values)])
    ys = chain.from_iterable(ys)
    max_y = float('-inf')  # Hack for starting w/ INT64_MIN
    for y in ys:
        if max_y < y:
            max_y = y
    chart.range = (max_y * 0.0, max_y * 1.20)
    chart.render_to_file('%s.svg' % workload)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description='Plot Kafka OpenMessaging Benchmark results')

    parser.add_argument('--acks', dest='ack', help='ACK level')
    parser.add_argument('--message-size', dest='msg_size',
                        help='Message size (100b or 1kb)')
    parser.add_argument('--results', dest='results_dir',
                        help='Directory containing the results')
    parser.add_argument('--durability', dest='durability',
                        help='Either \'fsync\' or \'nofsync\'')
    parser.add_argument('--files', nargs='+', dest='files', required=False, type=str,
                        help='Explicitly specify result files to plot')
    parser.add_argument('--series-labels', nargs='+', dest='labels', required=False,
                        type=str, help='Labels for the series instead of file names on the plot')
    parser.add_argument('--series-opts', nargs='+', dest='opts', required=False,
                        type=str, help='Options for each series: Dashed or Filled')
    args = parser.parse_args()

    if args.labels != None and len(args.labels) != len(args.files):
        sys.exit('Number of labels specified != Number of input files')

    aggregate = []

    if args.files is None:
        file_name_template = f'{args.msg_size}-run-[0123456789]+-{args.durability}-{args.ack}-acks.json'

        # Get list of directories
        for (dirpath, dirnames, filenames) in walk(args.results_dir):
            for file in filenames:
                if re.match(file_name_template, file):
                    file_path = path.join(dirpath, file)
                    data = json.load(open(file_path))
                    data['file'] = file
                    aggregate.append(data)
    else:
        for idx, file in enumerate(args.files):
            data = json.load(open(file))
            if args.labels != None:
                data['file'] = args.labels[idx]
            else:
                data['file'] = file
            aggregate.append(data)

    stats_pub_rate = []
    stats_con_rate = []
    stats_backlog = []
    stats_lat_p99 = []
    stats_lat_p999 = []
    stats_lat_p9999 = []
    stat_lat_avg = []
    stat_lat_max = []
    stat_lat_quantile = []
    stat_e2e_lat_quantile = []
    drivers = []

    pub_rate_avg = {}
    pub_rate_avg["Throughput (MB/s)"] = []

    # colors = ['#66CC69', '#173361', '#D8365D']
    colors = ['#66CC69', '#667C69', '#173361', '#D8365D', '#78365D']

    # Aggregate across all runs
    count = 0
    for data in aggregate:
        stats_pub_rate.append(data['publishRate'])
        stats_con_rate.append(data['consumeRate'])
        stats_backlog.append(data['backlog'])
        stats_lat_p99.append(data['publishLatency99pct'])
        stats_lat_p999.append(data['publishLatency999pct'])
        stats_lat_p9999.append(data['publishLatency9999pct'])
        stat_lat_avg.append(data['publishLatencyAvg'])
        stat_lat_max.append(data['publishLatencyMax'])

        stat_lat_quantile.append(data['aggregatedPublishLatencyQuantiles'])
        stat_e2e_lat_quantile.append(
            data['aggregatedEndToEndLatencyQuantiles'])
        drivers.append(data['file'])

        # if (count >= len(aggregate)/2):
        #     pub_rate_avg[args.barlabels[1]].append(
        #         sum(data['publishRate'])/len(data['publishRate']))
        # else:
        #     pub_rate_avg[args.barlabels[0]].append(
        #         sum(data['publishRate'])/len(data['publishRate']))
        pub_rate_avg["Throughput (MB/s)"].append(
            {
                'value': (sum(data['publishRate'])/len(data['publishRate']) * 1024)/(1024.0 * 1024.0),
                'color': colors[count]
            })
        count = count + 1

    # Parse plot options
    opts = []
    if args.opts is None:
        for driver in drivers:
            opts.append({})
    else:
        for opt in args.opts:
            if opt == 'Dashed':
                opts.append({'width': 2, 'dasharray': '3, 6, 12, 24'})
            else:
                opts.append({})

    # Generate publish rate bar-chart
    svg = f'{args.msg_size}-{args.durability}-{args.ack}-acks-publish-rate-bar'
    print(pub_rate_avg)
    create_bar_chart(svg, 'Throughput (MB/s)', 'MB/s',
                     drivers, pub_rate_avg)

    # Generate latency quantiles
    time_series = zip(drivers, stat_lat_quantile, opts)
    svg = f'{args.msg_size}-{args.durability}-{args.ack}-acks-latency-quantile'
    create_quantile_chart(svg, 'Publish Latency Quantiles',
                          y_label='Latency (ms)',
                          time_series=time_series)

    time_series = zip(drivers, stat_e2e_lat_quantile, opts)
    svg = f'{args.msg_size}-{args.durability}-{args.ack}-acks-e2e-latency-quantile'
    create_quantile_chart(svg, 'End-to-End Latency Quantiles',
                          y_label='Latency (ms)',
                          time_series=time_series)

    # Generate p99 latency time-series
    svg = f'{args.msg_size}-{args.durability}-{args.ack}-acks-publish-latency-p99'
    time_series = zip(drivers, stats_lat_p99)
    create_chart(svg, 'Publish Latency - 99th Percentile',
                 y_label='Latency (ms)', time_series=time_series)

    # Generate avg E2E latency time-series
    svg = f'{args.msg_size}-{args.durability}-{args.ack}-acks-e2e-latency-avg'
    time_series = zip(drivers, stat_lat_avg)
    create_chart(svg, 'End-to-end Latency - Average',
                 y_label='Latency (ms)', time_series=time_series)

    # Generate publish rate
    svg = f'{args.msg_size}-{args.durability}-{args.ack}-acks-publish-rate'
    time_series = zip(drivers, stats_pub_rate)
    create_chart(svg, 'Publish Rate',
                 y_label='Message/s', time_series=time_series)

    # Generate consume + backlog rate
    svg = f'{args.msg_size}-{args.durability}-{args.ack}-acks-consume-rate-backlog'
    labels_con = []
    labels_backlog = []

    for x in drivers:
        labels_con.append(x + " - Consume Rate")
        labels_backlog.append(x + " - Backlog")

    time_series = zip(labels_con, stats_con_rate,
                      labels_backlog, stats_backlog)
    create_multi_chart(svg, 'Consume rate (Messages/s - Left) w/ Backlog (No. of Messages - Right)',
                       'Consume - Messages/s', 'Backlog - Messages', time_series)
