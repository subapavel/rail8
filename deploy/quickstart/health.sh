#!/usr/bin/env bash
# The node's health check in the compose stacks: GET /health on the admin port of this container's loopback, healthy on a 200.
# The admin API serves HTTPS when RAIL8_ADMIN_TLS_CERT is set, plain HTTP otherwise; the image has no curl, so plain HTTP goes
# over bash's /dev/tcp and HTTPS through openssl s_client. The certificate is not checked: the request carries no secret and
# never leaves the container.
set -eu
port="${RAIL8_ADMIN_PORT:-8081}"
request=$'GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n'
if [ -n "${RAIL8_ADMIN_TLS_CERT:-}" ]; then
  printf '%s' "$request" | openssl s_client -quiet -connect "127.0.0.1:$port" -servername localhost 2>/dev/null | head -n 1 | grep -q ' 200 '
else
  exec 3<>"/dev/tcp/127.0.0.1/$port"
  printf '%s' "$request" >&3
  head -n 1 <&3 | grep -q ' 200 '
fi
