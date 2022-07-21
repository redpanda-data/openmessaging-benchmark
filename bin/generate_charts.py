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

import glob
import json
import math
import argparse
import sys

import pygal
from pygal.style import Style
from itertools import chain
from os import path
from jinja2 import Template
from collections import defaultdict

graph_colors = ['#545454', # dark gray
                '#e2401b', # redpanda red1
                '#78909c',  # light gray
                '#ED7F66', # redpanda red2
                '#0097A7', # blue
                '#ffab40', # yellow
                '#00ffff', # cyan
                '#ff00ff'] # magenta
chartStyle = Style(
    background='transparent',
    plot_background='transparent',
    font_family='googlefont:Montserrat',
    colors=graph_colors,
    label_font_size=16,
    legend_font_size=16,
    major_label_font_size=16,
)

#theme = pygal.style.CleanStyle
theme = chartStyle
fill = False
output = ''
file_list = defaultdict(list)
charts = defaultdict(list)
coalesce = False


def _clean_xy_values(values):
    values = sorted((float(x), y) for x, y in values.items())
    # do not restrict to any percentiles. show the max; the outliers
    # are where the goodies lie
    def _x_axis(x):
        if x < 100.0: x = math.log10(100 / (100 - x))
        # clamp
        return min(x, 100.0)
 
    xy_values = [(_x_axis(x), y) for x, y in values]
    needle_idx = len(xy_values)-1
    while needle_idx > 0:
        x = round(xy_values[needle_idx][0],2)
        if x < 100.00:
            return xy_values[0:needle_idx]
        needle_idx = needle_idx-1
    return xy_values


def create_quantile_chart(title, y_label, time_series):
    def _fmt_val(x):
        return 'p{:,.3f}'.format(100.0 - (100.0 / (10**x)))
    chart = pygal.XY(style=theme,
                     dots_size=2,
                     legend_at_bottom=True,
                     truncate_legend=37,
                     x_value_formatter=_fmt_val,
                     show_dots=True,
                     fill=fill,
                     stroke_style={'width': 2},
                     show_y_guides=True,
                     show_x_guides=True)
    # chart = pygal.XY()
    chart.title = title
    chart.human_readable = True
    chart.y_title = y_label
    chart.x_title = 'Percentile'
    chart.x_labels = [0.31, 1, 3, 5]
    chart.x_label_rotation=20
    chart.x_labels_major_count=4
    chart.show_minor_x_labels=False
    chart.tooltip_border_radius=10
    for label, values, opts in time_series:
        xy_values = _clean_xy_values(values)  
        chart.add(label, xy_values, stroke_style=opts)

    return chart.render(disable_xml_declaration=True)


def create_multi_chart(title, y_label_1, y_label_2,
                       time_series):
    chart = pygal.XY(style=theme,
                     dots_size=1,
                     show_dots=False,
                     stroke_style={'width': 2},
                     fill=fill,
                     legend_at_bottom=True,
                     show_x_guides=False,
                     show_y_guides=True)
    chart.title = title
    chart.human_readable = True
    chart.x_title = 'Time (seconds)'

    ys_1 = []
    ys_2 = []

    for label_1, values_1, label_2, values_2 in time_series:
        ys_1.append(values_1)
        chart.add(label_1, [(10 * x, y) for x, y in enumerate(values_1)])
        chart.add(label_2, [(10 * x, y) for x, y in enumerate(values_2)],
                  secondary=True)

    ys_1 = chain.from_iterable(ys_1)
    ys_2 = chain.from_iterable(ys_2)
    max_y_1 = float('-inf')  # Hack for starting w/ INT64_MIN
    max_y_2 = max_y_1
    for y in ys_1:
        if max_y_1 < y:
            max_y_1 = y
    chart.range = (0, max_y_1 * 1.20)
    return chart.render(disable_xml_declaration=True)


def create_bar_chart(title, y_label, x_label, data):
    chart = pygal.Bar(style=theme,
                      dots_size=1,
                      show_dots=False,
                      stroke_style={'width': 2},
                      fill=fill,
                      show_legend=False,
                      show_x_guides=False,
                      show_y_guides=True)
    chart.title = title
    chart.x_labels = x_label
    chart.value_formatter = lambda y: "{:,.0f}".format(y)

    for label, points in data.items():
        chart.add(label, points)

    return chart.render(disable_xml_declaration=True)


def create_chart(title, y_label, time_series):
    chart = pygal.XY(style=theme,
                     dots_size=1,
                     show_dots=False,
                     stroke_style={'width': 2},
                     fill=fill,
                     legend_at_bottom=True,
                     show_x_guides=False,
                     show_y_guides=True)
    chart.title = title

    chart.human_readable = True
    chart.y_title = y_label
    chart.x_title = 'Time (seconds)'
    
    ys = []
    for label, values in time_series:
        ys.append(values)
        chart.add(label, [(10 * x, y) for x, y in enumerate(values)])
    ys = chain.from_iterable(ys)
    max_y = float('-inf')  # Hack for starting w/ INT64_MIN
    for y in ys:
        if max_y < y:
            max_y = y
    chart.range = (max_y * 0.0, max_y * 1.20)

    return chart.render(disable_xml_declaration=True)


def generate_charts(files):
    workloads = {}

    # Charts are labeled based on benchmark names, we need them
    # to be unique. A combination of (driver, workload) defines
    # a unique benchmark and name is "{driver}-{workload}".
    benchmark_names = set()
    for file in sorted(files):
        data = json.load(open(file))
        data['workload'] = data['workload'].replace('/', '-')
        begin_time = data['beginTime'] if 'beginTime' in data.keys() else ""
        rp_version = data['version'] if 'version' in data.keys() and data['version'] else "unknown_version"
        unique_name = "{ver}-{ts}-{driver}-{workload}".format(ver=rp_version, ts=begin_time, driver=data['driver'], workload=data['workload'])
        # name used as chart label.
        name = "{ver}-{driver}-{workload}".format(ver=rp_version, driver=data['driver'], workload=data['workload'])
        if unique_name in benchmark_names:
            print(f"WARN: Duplicate benchmark found: {name} in file {file}", file=sys.stderr)

        benchmark_names.add(unique_name)

        if coalesce:
            workload = 'All Workloads'
        else:
            workload = data['workload']

        benchmark_names.add(name)
        data['name'] = name

        if workload in workloads:
            workloads[workload].append(data)
        else:
            workloads[workload] = [data]

    for workload in workloads:
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
        pub_rate_avg["Throughput (MB/s): higher is better"] = []

        # Aggregate across all runs
        count = 0
        curated_metrics = {}  # to dump to stdout
        metrics_of_interest = [
            "version",
            "beginTime",
            "endTime",
            "aggregatedPublishLatencyAvg",
            "aggregatedPublishLatency50pct",
            "aggregatedPublishLatency99pct",
            "aggregatedPublishLatencyMax",
            "aggregatedPublishDelayLatencyAvg",
            "aggregatedPublishDelayLatency50pct",
            "aggregatedPublishDelayLatency99pct",
            "aggregatedEndToEndLatencyAvg",
            "aggregatedEndToEndLatency50pct",
            "aggregatedEndToEndLatency99pct",
            "aggregatedEndToEndLatency9999pct",
            "aggregatedEndToEndLatencyMax",
        ]

        for data in workloads[workload]:
            metrics = dict()
            curated_metrics[data['name']] = metrics
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
            drivers.append(data['name'])
            throughput = (sum(data['publishRate']) / len(data['publishRate']) * 1024) / (1024.0 * 1024.0)
            pub_rate_avg["Throughput (MB/s): higher is better"].append({
                'value': throughput,
                'color': graph_colors[count % len(graph_colors)]
            })
            count = count + 1
            for metric_key in metrics_of_interest:
                if metric_key in data.keys():
                    metrics[metric_key] = data[metric_key]
            metrics["throughputMBps"] = throughput

        # OMB tooling depends on the output of this script, do not print extra stuff to stdout unless
        # you fully understand what you are doing.
        print(json.dumps(curated_metrics, indent=2))

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
        charts[workload] = [create_bar_chart('Throughput (MB/s): higher is better', 'MB/s', drivers,
                                                     pub_rate_avg)]

        # Generate latency quantiles
        time_series = zip(drivers, stat_lat_quantile, opts)
        charts[workload].append(create_quantile_chart('Publish Latency Percentiles: lower is better',
                                                              y_label='Latency (ms)',
                                                              time_series=time_series))

        time_series = zip(drivers, stat_e2e_lat_quantile, opts)
        charts[workload].append(create_quantile_chart('End-to-End Latency Percentiles: lower is better',
                                      y_label='Latency (ms)',
                                      time_series=time_series))

        # Generate p99 latency time-series
        time_series = zip(drivers, stats_lat_p99)
        charts[workload].append(create_chart('Publish Latency - 99th Percentile: lower is better',
                     y_label='Latency (ms)',
                     time_series=time_series))

        # Generate avg E2E latency time-series
        time_series = zip(drivers, stat_lat_avg)
        charts[workload].append(create_chart('End-to-end Latency - Average: lower is better',
                     y_label='Latency (ms)',
                     time_series=time_series))

        # Generate publish rate
        time_series = zip(drivers, stats_pub_rate)
        charts[workload].append(create_chart('Publish Rate: higher is better',
                     y_label='Message/s',
                     time_series=time_series))

        # Generate consume + backlog rate
        labels_con = []
        labels_backlog = []

        for x in drivers:
            labels_con.append(x + " - Consume Rate")
            labels_backlog.append(x + " - Backlog")

        time_series = zip(labels_con, stats_con_rate, labels_backlog,
                          stats_backlog)
        charts[workload].append(create_multi_chart(
            'Consume rate (Messages/s - Left) w/ Backlog (No. of Messages - Right)',
            'Consume - Messages/s', 'Backlog - Messages', time_series))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description='Plot Kafka OpenMessaging Benchmark results')

    parser.add_argument(
        '--results',
        dest='results_dir',
        required=True,
        help='Directory containing the results for both Redpanda and Kafka')
    parser.add_argument('--series-opts',
                        nargs='+',
                        dest='opts',
                        required=False,
                        type=str,
                        help='Options for each series: Dashed or Filled')
    parser.add_argument('--output',
                        dest='output',
                        required=False,
                        type=str,
                        help='Location where all output will be stored')
    parser.add_argument('--coalesce-workloads',
                        dest='coalesce',
                        action='store_true',
                        help='Specify put all workloads on a single set of charts')

    args = parser.parse_args()

    prefixes = {}

    if args.output != '':
        output = path.join(args.output, '')

    coalesce = args.coalesce

    # Recursively fetch all json files in the results dir.
    filelist = glob.iglob(path.join(args.results_dir, "**/*.json"), recursive=True)

    generate_charts(filelist)

    html = '''
<html>
<head>
<script>
/*! pygal.js           2015-10-30 */
(function(){var a,b,c,d,e,f,g,h,i,j,k;i="http://www.w3.org/2000/svg",k="http://www.w3.org/1999/xlink",a=function(a,b){return null==b&&(b=null),b=b||document,Array.prototype.slice.call(b.querySelectorAll(a),0).filter(function(a){return a!==b})},e=function(a,b){return(a.matches||a.matchesSelector||a.msMatchesSelector||a.mozMatchesSelector||a.webkitMatchesSelector||a.oMatchesSelector).call(a,b)},h=function(a,b){return null==b&&(b=null),Array.prototype.filter.call(a.parentElement.children,function(c){return c!==a&&(!b||e(c,b))})},Array.prototype.one=function(){return this.length>0&&this[0]||{}},f=5,j=null,g=/translate\((\d+)[ ,]+(\d+)\)/,b=function(a){return(g.exec(a.getAttribute("transform"))||[]).slice(1).map(function(a){return+a})},c=function(c){var d,g,l,m,n,o,p,q,r,s,t,u,v,w,x,y,z,A,B,C,D,E,F,G,H;for(a("svg",c).length?(o=a("svg",c).one(),q=o.parentElement,g=o.viewBox.baseVal,d=q.getBBox(),w=function(a){return(a-g.x)/g.width*d.width},x=function(a){return(a-g.y)/g.height*d.height}):w=x=function(a){return a},null!=(null!=(E=window.pygal)?E.config:void 0)?null!=window.pygal.config.no_prefix?l=window.pygal.config:(u=c.id.replace("chart-",""),l=window.pygal.config[u]):l=window.config,s=null,n=a(".graph").one(),t=a(".tooltip",c).one(),F=a(".reactive",c),y=0,B=F.length;B>y;y++)m=F[y],m.addEventListener("mouseenter",function(a){return function(){return a.classList.add("active")}}(m)),m.addEventListener("mouseleave",function(a){return function(){return a.classList.remove("active")}}(m));for(G=a(".activate-serie",c),z=0,C=G.length;C>z;z++)m=G[z],p=m.id.replace("activate-serie-",""),m.addEventListener("mouseenter",function(b){return function(){var d,e,f,g,h,i,j,k;for(i=a(".serie-"+b+" .reactive",c),e=0,g=i.length;g>e;e++)d=i[e],d.classList.add("active");for(j=a(".serie-"+b+" .showable",c),k=[],f=0,h=j.length;h>f;f++)d=j[f],k.push(d.classList.add("shown"));return k}}(p)),m.addEventListener("mouseleave",function(b){return function(){var d,e,f,g,h,i,j,k;for(i=a(".serie-"+b+" .reactive",c),e=0,g=i.length;g>e;e++)d=i[e],d.classList.remove("active");for(j=a(".serie-"+b+" .showable",c),k=[],f=0,h=j.length;h>f;f++)d=j[f],k.push(d.classList.remove("shown"));return k}}(p)),m.addEventListener("click",function(b,d){return function(){var e,f,g,h,i,j,k,l,m,n,o;for(g=a("rect",b).one(),h=""!==g.style.fill,g.style.fill=h?"":"transparent",m=a(".serie-"+d+" .reactive",c),i=0,k=m.length;k>i;i++)f=m[i],f.style.display=h?"":"none";for(n=a(".text-overlay .serie-"+d,c),o=[],j=0,l=n.length;l>j;j++)e=n[j],o.push(e.style.display=h?"":"none");return o}}(m,p));for(H=a(".tooltip-trigger",c),A=0,D=H.length;D>A;A++)m=H[A],m.addEventListener("mouseenter",function(a){return function(){return s=r(a)}}(m));return t.addEventListener("mouseenter",function(){return null!=s?s.classList.add("active"):void 0}),t.addEventListener("mouseleave",function(){return null!=s?s.classList.remove("active"):void 0}),c.addEventListener("mouseleave",function(){return j&&clearTimeout(j),v(0)}),n.addEventListener("mousemove",function(a){return!j&&e(a.target,".background")?v(1e3):void 0}),r=function(c){var d,e,g,m,n,o,p,r,s,u,v,y,z,A,B,C,D,E,F,G,H,I,J,K,L,M,N,O,P,Q,R,S,T,U,V,W,X,Y,Z,$,_;for(clearTimeout(j),j=null,t.style.opacity=1,t.style.display="",G=a("g.text",t).one(),C=a("rect",t).one(),G.innerHTML="",v=h(c,".label").one().textContent,N=h(c,".x_label").one().textContent,J=h(c,".value").one().textContent,O=h(c,".xlink").one().textContent,D=null,q=c,I=[];q&&(I.push(q),!q.classList.contains("series"));)q=q.parentElement;if(q)for(X=q.classList,R=0,S=X.length;S>R;R++)if(g=X[R],0===g.indexOf("serie-")){D=+g.replace("serie-","");break}for(y=null,null!==D&&(y=l.legends[D]),o=0,u=[[v,"label"]],Y=J.split("\\n"),r=V=0,T=Y.length;T>V;r=++V)E=Y[r],u.push([E,"value-"+r]);for(l.tooltip_fancy_mode&&(u.push([O,"xlink"]),u.unshift([N,"x_label"]),u.unshift([y,"legend"])),H={},W=0,U=u.length;U>W;W++)Z=u[W],s=Z[0],z=Z[1],s&&(F=document.createElementNS(i,"text"),F.textContent=s,F.setAttribute("x",f),F.setAttribute("dy",o),F.classList.add(0===z.indexOf("value")?"value":z),0===z.indexOf("value")&&l.tooltip_fancy_mode&&F.classList.add("color-"+D),"xlink"===z?(d=document.createElementNS(i,"a"),d.setAttributeNS(k,"href",s),d.textContent=void 0,d.appendChild(F),F.textContent="Link >",G.appendChild(d)):G.appendChild(F),o+=F.getBBox().height+f/2,e=f,void 0!==F.style.dominantBaseline?F.style.dominantBaseline="text-before-edge":e+=.8*F.getBBox().height,F.setAttribute("y",e),H[z]=F);return K=G.getBBox().width+2*f,p=G.getBBox().height+2*f,C.setAttribute("width",K),C.setAttribute("height",p),H.value&&H.value.setAttribute("dx",(K-H.value.getBBox().width)/2-f),H.x_label&&H.x_label.setAttribute("dx",K-H.x_label.getBBox().width-2*f),H.xlink&&H.xlink.setAttribute("dx",K-H.xlink.getBBox().width-2*f),M=h(c,".x").one(),Q=h(c,".y").one(),L=parseInt(M.textContent),M.classList.contains("centered")?L-=K/2:M.classList.contains("left")?L-=K:M.classList.contains("auto")&&(L=w(c.getBBox().x+c.getBBox().width/2)-K/2),P=parseInt(Q.textContent),Q.classList.contains("centered")?P-=p/2:Q.classList.contains("top")?P-=p:Q.classList.contains("auto")&&(P=x(c.getBBox().y+c.getBBox().height/2)-p/2),$=b(t.parentElement),A=$[0],B=$[1],L+K+A>l.width&&(L=l.width-K-A),P+p+B>l.height&&(P=l.height-p-B),0>L+A&&(L=-A),0>P+B&&(P=-B),_=b(t),m=_[0],n=_[1],m===L&&n===P?c:(t.setAttribute("transform","translate("+L+" "+P+")"),c)},v=function(a){return j=setTimeout(function(){return t.style.display="none",t.style.opacity=0,null!=s&&s.classList.remove("active"),j=null},a)}},d=function(){var b,d,e,f,g;if(d=a(".pygal-chart"),d.length){for(g=[],e=0,f=d.length;f>e;e++)b=d[e],g.push(c(b));return g}},"loading"!==document.readyState?d():document.addEventListener("DOMContentLoaded",function(){return d()}),window.pygal=window.pygal||{},window.pygal.init=c,window.pygal.init_svg=d}).call(this);
</script>
<link rel="stylesheet" href="https://maxcdn.bootstrapcdn.com/bootstrap/4.0.0/css/bootstrap.min.css">
</head>
<body >
<div class="container">
<h1> {{title}} </h1>
  {% for workload in charts %}
    <div class='row'>
    <div class='well col-md-12'><h2>{{workload}}<h2></div>
    {% for chart in charts[workload] %}
      {{chart}}
    {% endfor %}
    <div>
  {% endfor %}
</div>
</body>
</html>
    '''

    template = Template(html)

    index_html = template.render(charts=charts, title="Charts")

    f = open(f"{output}index.html", "w")
    f.write(index_html)
    f.close()
