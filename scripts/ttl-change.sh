#!/usr/bin/env bash
# Records, once per second, what dig gets for one name from the lab DNS server, so the TTL-change
# experiment shows when a client that never caches sees the new answer (Module 6, Section 8).
# Run it in a second terminal while ./gradlew -q ttlLive --args='config/ttl-live.properties' runs.
#
# Usage: scripts/ttl-change.sh <port> <name> <seconds> <output file> [--run] [--yes]
#   default   a dry run: print the plan and the dig command, send nothing
#   --run     send the queries; asks for confirmation on the terminal first
#   --yes     skip the question (for a reader who has already read the plan)
#
# Guardrails: the server is always 127.0.0.1 and the port must be 1024 to 65535, so no query ever
# leaves this machine and no privileged port is touched; the name must lie under seatwave.example;
# at most 300 queries, one per second; the output file must not exist yet; an audit line is
# appended next to the output; any failure exits non-zero. dig never changes anything: it only asks.
set -euo pipefail

readonly MAX_SECONDS=300
readonly LOWEST_PORT=1024
readonly HIGHEST_PORT=65535
readonly NAME_PATTERN='^([a-z0-9-]+\.)*seatwave\.example\.?$'
readonly DIG_TIMEOUT_SECONDS=1

usage() {
  echo "usage: $0 <port> <name> <seconds> <output file> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 4 ]] || usage
port="$1"
name="$2"
seconds="$3"
out_file="$4"
shift 4
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done

# Check before: the tool, the inputs and the output.
command -v dig >/dev/null || { echo "dig is not installed" >&2; exit 1; }
[[ "$port" =~ ^[1-9][0-9]*$ ]] && (( port >= LOWEST_PORT && port <= HIGHEST_PORT )) \
  || { echo "the port must be $LOWEST_PORT to $HIGHEST_PORT" >&2; exit 2; }
[[ "$name" =~ $NAME_PATTERN ]] || { echo "the name must lie under seatwave.example: $name" >&2; exit 2; }
[[ "$seconds" =~ ^[1-9][0-9]*$ ]] && (( seconds >= 1 && seconds <= MAX_SECONDS )) \
  || { echo "seconds must be 1 to $MAX_SECONDS" >&2; exit 2; }
[[ ! -e "$out_file" ]] || { echo "refusing to overwrite $out_file" >&2; exit 2; }

dig_command=(dig @127.0.0.1 -p "$port" "$name" A +norecurse +noall +answer +time="$DIG_TIMEOUT_SECONDS" +tries=1)
echo "plan: $seconds queries, one per second, to 127.0.0.1:$port for $name A"
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing sent. Each query would be:"
  echo "  ${dig_command[*]}"
  echo "add --run to send them"
  exit 0
fi

if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Send $seconds queries to 127.0.0.1:$port? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing sent" >&2; exit 1; }
fi

mkdir -p "$(dirname "$out_file")"
audit_file="$(dirname "$out_file")/audit.log"
started=$(date +%s)
for (( query = 1; query <= seconds; query++ )); do
  # One line per query: seconds since the start, then dig's answer (name, TTL, class, type, address).
  # dig 9.10 prints its comment lines even with +noall, so only the record lines are kept.
  answer=$("${dig_command[@]}" | grep -v '^;' | grep -v '^$' | tr -s '\t ' ' ' || true)
  printf '%3d %s\n' "$(( $(date +%s) - started ))" "${answer:-no answer}" >> "$out_file"
  sleep 1
done

# Check after: one line per query, or the run failed somewhere.
written=$(wc -l < "$out_file" | tr -d ' ')
echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) sent $seconds queries to 127.0.0.1:$port for $name; wrote $written lines to $out_file" >> "$audit_file"
if [[ "$written" != "$seconds" ]]; then
  echo "expected $seconds lines, but $out_file has $written" >&2
  exit 1
fi
echo "wrote $written lines to $out_file; audit line added to $audit_file"
