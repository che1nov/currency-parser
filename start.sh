#!/bin/sh
set -eu
cd "$(dirname "$0")"
if [ -f currency-parser.jar ]; then
    exec java -jar currency-parser.jar "$@"
fi
if [ ! -f target/currency-parser-1.0.0.jar ]; then
    mvn -B -ntp package
fi
exec java -jar target/currency-parser-1.0.0.jar "$@"
