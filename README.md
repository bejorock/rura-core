# rura-core


## run grafana
docker run \
  --detach \
   --publish=80:80 \
   --publish=81:81 \
   --publish=8125:8125/udp \
   --publish=8126:8126 \
   --name kamon \
   --volume=/Users/ranalubis/Workspaces/appfiles/grafana/data/whisper:/opt/graphite/storage/whisper \
   --volume=/Users/ranalubis/Workspaces/appfiles/grafana/data/elasticsearch:/var/lib/elasticsearch \
   --volume=/Users/ranalubis/Workspaces/appfiles/grafana/data/grafana:/opt/grafana/data \
   --volume=/Users/ranalubis/Workspaces/appfiles/grafana/log/graphite:/opt/graphite/storage/log \
   --volume=/Users/ranalubis/Workspaces/appfiles/grafana/log/elasticsearch:/var/log/elasticsearch \
   kamon/grafana_graphite

docker run \
  --detach \
   --publish=80:80 \
   --publish=81:81 \
   --publish=8125:8125/udp \
   --publish=8126:8126 \
   --name kamon \
   kamon/grafana_graphite

docker run \
  --detach \
   --publish=80:80 \
   --name kamon \
   kamon/grafana_graphite

docker run -d \
  --name influxdb-grafana \
  -p 3003:9000 \
  -p 3004:8083 \
  -p 8086:8086 \
  -p 22022:22 \
  -p 8125:8125/udp \
  samuelebistoletti/docker-statsd-influxdb-grafana

docker run -d \
  --name influxdb \
  -p 3004:8083 \
  -p 8086:8086 \
  -p 8125:8125/udp \
  samuelebistoletti/docker-statsd-influxdb-grafana