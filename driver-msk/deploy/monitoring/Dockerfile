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

FROM grafana/grafana:7.0.5

USER root

# Install the Grafana Image Rendering Plugin
ENV GF_PATHS_PLUGINS="/var/lib/grafana-plugins"
RUN mkdir -p "$GF_PATHS_PLUGINS" && \
    chown -R grafana:grafana "$GF_PATHS_PLUGINS"
RUN echo "http://dl-cdn.alpinelinux.org/alpine/edge/community" >> /etc/apk/repositories && \
    echo "http://dl-cdn.alpinelinux.org/alpine/edge/main" >> /etc/apk/repositories && \
    echo "http://dl-cdn.alpinelinux.org/alpine/edge/testing" >> /etc/apk/repositories && \
    apk --no-cache  upgrade && \
    apk add --no-cache udev ttf-opensans chromium && \
    rm -rf /tmp/* && \
    rm -rf /usr/share/grafana/tools/phantomjs;
USER grafana
ENV GF_RENDERER_PLUGIN_CHROME_BIN="/usr/bin/chromium-browser"
RUN grafana-cli \
        --pluginsDir "$GF_PATHS_PLUGINS" \
        --pluginUrl https://github.com/grafana/grafana-image-renderer/releases/latest/download/plugin-linux-x64-glibc-no-chromium.zip \
        plugins install grafana-image-renderer;

USER root
COPY dashboards.yaml /etc/grafana/provisioning/dashboards/dashboards.yaml
RUN  apk add curl
USER grafana
COPY grafana.ini /etc/grafana/grafana.ini
COPY dashboards/* /var/lib/grafana/dashboards/
COPY start.sh /start.sh

EXPOSE 3000

ENV PROMETHEUS_URL http://prometheus:9090

ENTRYPOINT ["/start.sh"]
