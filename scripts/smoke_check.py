#!/usr/bin/env python3
"""Необязательная проверка JAR с реальным ЦБ. Требует Python 3, Java 17 и интернет."""
import json
import subprocess
import tempfile
import time
from decimal import Decimal, ROUND_HALF_UP
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

PROJECT = Path(__file__).resolve().parents[1]
JAR = PROJECT / "currency-parser.jar"
if not JAR.exists():
    JAR = PROJECT / "target/currency-parser-1.1.0.jar"
BASE = "http://127.0.0.1:18080"


def request(path, body=None, expected=200):
    data = None if body is None else json.dumps(body).encode()
    req = Request(BASE + path, data=data, headers={"Content-Type": "application/json"})
    try:
        response = urlopen(req, timeout=25)
    except HTTPError as error:
        response = error
    with response:
        result = json.load(response)
        assert response.status == expected, (response.status, result)
        return result


def start(directory, automatic):
    log = open(Path(directory) / ("auto.log" if automatic else "restart.log"), "w")
    process = subprocess.Popen([
        "java", "-jar", str(JAR), "--server.port=18080",
        "--collector.scheduling-enabled=" + str(automatic).lower()
    ], cwd=directory, stdout=log, stderr=subprocess.STDOUT)
    log.close()
    return process


def stop(process):
    process.terminate()
    try:
        process.wait(timeout=25)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
        raise AssertionError("Application did not stop gracefully")


def ready(process, completed=False):
    deadline = time.monotonic() + 35
    while time.monotonic() < deadline:
        assert process.poll() is None, "Application exited before readiness"
        try:
            state = request("/api/status")
            if not completed or state["state"] == "SUCCEEDED":
                return state
            assert state["state"] != "FAILED", state
        except (URLError, ConnectionError):
            pass
        time.sleep(0.2)
    raise AssertionError("Application did not become ready")


def main():
    with tempfile.TemporaryDirectory(prefix="currency-parser-smoke-") as directory:
        process = start(directory, automatic=True)
        try:
            state = ready(process, completed=True)
            assert state["savedCount"] == 5
            today = request("/answer")
            assert len(today) == 5
            print("PASS: startup schedule collected five real CBR rates", flush=True)

            body = {"date": "2024-03-05", "sourceUrl": "https://www.cbr.ru/currency_base/daily/"}
            result = request("/api/collect", body)
            assert result["savedCount"] == 5
            saved = request("/answer?date=2024-03-05&sort=rate&direction=desc")
            assert len(saved) == 5
            values = [Decimal(str(row["rate"])) for row in saved]
            assert values == sorted(values, reverse=True)
            usd = request("/answer?date=2024-03-05&currency=USD")
            assert len(usd) == 1 and usd[0]["currency"] == "USD"
            for row in saved:
                rate, previous = Decimal(str(row["rate"])), Decimal(str(row["previousRate"]))
                assert rate > 0 and previous > 0
                assert Decimal(str(row["change"])) == rate - previous
                expected = ((rate - previous) / previous * 100).quantize(Decimal("0.000001"), ROUND_HALF_UP)
                assert Decimal(str(row["changePercent"])) == expected
            print("PASS: historical collection, currency filter, numeric sorting and daily change", flush=True)

            last_result = request("/api/collect", body)
            saved = request("/answer?date=2024-03-05")
            assert len(saved) == 5
            request("/answer?currency=BTC", expected=400)
            request("/answer?sort=unknown", expected=400)
            request("/api/collect", {"date": "bad"}, expected=400)
            assert request("/answer?date=2020-01-01") == []
            print("PASS: repeated collection has no duplicates; invalid requests return HTTP 400", flush=True)
            assert request("/api/status")["snapshotError"] is None
            snapshot_path = Path(directory) / "data/snapshots/2024-03-05.json"
            snapshot_text = snapshot_path.read_text()
            snapshot = json.loads(snapshot_text)
            expected_snapshot = [{key: value for key, value in row.items() if key != "baseCurrency"}
                                 for row in last_result["rates"]]
            assert snapshot == expected_snapshot
            journal = request("/api/journal")
            assert journal["running"] and journal["daemon"] and journal["rejectedEvents"] == 0
            assert journal["error"] is None
            print("PASS: JSON snapshot matches collected rates; standalone journal thread is a daemon", flush=True)
        except Exception:
            print((Path(directory) / "auto.log").read_text())
            raise
        finally:
            stop(process)

        events_path = Path(directory) / "data/collection-events.jsonl"
        events_text = events_path.read_text()
        events = [json.loads(line) for line in events_text.splitlines()]
        assert [event["event"] for event in events] == ["STARTED", "SUCCEEDED"] * 3
        assert all(event["daemon"] and event["thread"] == "collection-journal" for event in events)
        print("PASS: graceful shutdown drained all six journal events to file", flush=True)

        process = start(directory, automatic=False)
        try:
            ready(process)
            assert request("/answer?date=2024-03-05") == saved
            assert request("/api/status")["state"] == "IDLE"
            assert snapshot_path.read_text() == snapshot_text
            assert events_path.read_text() == events_text
            print("PASS: file H2 retains identical results after restart, without fetching again", flush=True)
        except Exception:
            print((Path(directory) / "restart.log").read_text())
            raise
        finally:
            stop(process)
    print("PASS: both application processes stopped gracefully", flush=True)


if __name__ == "__main__":
    main()
