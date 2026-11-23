#!/usr/bin/env python3
"""Capture a Redis catalog fingerprint, verify a restored standby, and prepare its setup code."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import http.client
import json
import os
from pathlib import Path
import sys
from urllib.parse import urlsplit

from publish_update import Redis


class StandbyError(ValueError):
    """An error message safe to display to the operator."""


def read_config(path):
    config = {}
    for line in path.read_text().splitlines():
        if line.startswith("androplay.") and "=" in line:
            key, value = line.split("=", 1)
            config[key.removeprefix("androplay.")] = value.strip()
    return config


def endpoint(url):
    parsed = urlsplit(url)
    if parsed.scheme != "rediss" or not parsed.hostname or not parsed.password or parsed.path not in ("", "/", "/0"):
        raise StandbyError("Use an authenticated Redis TLS URL for database 0")
    return f"{parsed.hostname.lower()}:{parsed.port or 6379}"


def digest(value):
    data = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(data.encode()).hexdigest()


def fields(reply):
    if not isinstance(reply, list) or len(reply) % 2:
        raise StandbyError("Redis returned an invalid catalog record")
    return dict(zip(reply[::2], reply[1::2]))


def transaction(redis, commands):
    if redis.call("MULTI") != "OK":
        raise RuntimeError("Redis did not start the read transaction")
    for command in commands:
        if redis.call(*command) != "QUEUED":
            raise RuntimeError("Redis did not queue the catalog check")
    replies = redis.call("EXEC")
    if not isinstance(replies, list) or len(replies) != len(commands):
        raise RuntimeError("Redis did not return a complete catalog snapshot")
    return replies


def inspect(redis, prefix):
    index_key = prefix + "content"
    for _ in range(3):
        ids = redis.call("ZRANGE", index_key, 0, -1)
        index, counter, removed, shows, release, events, *items = transaction(redis, [
            ("ZRANGE", index_key, 0, -1, "WITHSCORES"),
            ("GET", prefix + "handle"),
            ("SMEMBERS", prefix + "removed"),
            ("HGETALL", prefix + "shows"),
            ("HGETALL", prefix + "release"),
            ("XREVRANGE", prefix + "events", "+", "-", "COUNT", 1),
            *(("HGETALL", prefix + "content:" + item) for item in ids),
        ])
        if index[::2] == ids:
            break
    else:
        raise RuntimeError("Catalog changed during three consecutive checks")

    catalog = {item_id: fields(item) for item_id, item in zip(ids, items)}
    handles = []
    for item_id, item in catalog.items():
        if item.get("id") != item_id or item.get("status") not in ("ready", "uploading"):
            raise StandbyError("Catalog index and content records are inconsistent")
        handle = int(item["handle"])
        if not 1 <= handle < 65535:
            raise StandbyError("A catalog playback handle is outside its valid range")
        handles.append(handle)
    if len(handles) != len(set(handles)):
        raise StandbyError("Catalog contains duplicate playback handles")
    # Offline LAN imports can have locally allocated handles above the Redis counter.
    # The counter must still match the source exactly, and allocated handle collisions are rejected.
    if int(counter or 0) < 0:
        raise StandbyError("Redis handle counter is invalid")

    sections = {
        "catalog": {"index": index, "records": catalog},
        "handle_counter": int(counter or 0),
        "tombstones": sorted(removed),
        "slideshows": fields(shows),
        "release": fields(release),
        "event_checkpoint": [[event_id, fields(event)] for event_id, event in events],
    }
    return {name: digest(value) for name, value in sections.items()}


def inspect_url(url, prefix):
    redis = Redis(url)
    try:
        return inspect(redis, prefix)
    finally:
        redis.buf.close()
        redis.sock.close()


def write_exclusive(path, text):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as output:
        output.write(text)


def verify(manifest, standby_url):
    if manifest.get("schema") != 1:
        raise StandbyError("Unsupported catalog manifest version")
    if endpoint(standby_url) == manifest["source"]:
        raise StandbyError("Standby endpoint must differ from the source")
    expected = manifest["sections"]
    actual = inspect_url(standby_url, manifest["prefix"])
    changed = sorted(name for name in actual.keys() | expected.keys() if actual.get(name) != expected.get(name))
    if changed:
        raise StandbyError("Standby differs from the captured source in " + ", ".join(changed))
    return len(actual)


def check_primary_fence(config):
    """Reject a reachable primary that still accepts the configured device credentials."""
    url = config["redisUrl"]
    try:
        redis = Redis(url)
    except RuntimeError as error:
        revoked = any(part in str(error).lower() for part in ("wrongpass", "invalid password", "user is disabled"))
        if not revoked:
            raise StandbyError("Primary did not confirm credential revocation") from error
    except OSError:
        print("Native primary fence could not be checked from this network. Using operator attestation.")
    else:
        redis.buf.close()
        redis.sock.close()
        raise StandbyError("Primary still accepts the old native Redis credentials")

    token = config.get("redisRestToken")
    if not token:
        return
    try:
        status, _ = rest_request(url, token, ["PING"])
    except OSError:
        print("HTTPS primary fence could not be checked from this network. Using operator attestation.")
        return
    if status != 401:
        raise StandbyError("Primary did not reject the old HTTPS credential")


def rest_request(url, token, command):
    host = urlsplit(url).hostname
    if not host.endswith(".upstash.io"):
        raise StandbyError("Redis HTTPS fallback requires an Upstash endpoint")
    server = http.client.HTTPSConnection(host, timeout=10)
    try:
        server.request("POST", "/", body=json.dumps(command), headers={
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json",
        })
        response = server.getresponse()
        return response.status, json.loads(response.read()) if response.status == 200 else None
    finally:
        server.close()


def verify_standby_https(manifest, url, token):
    if not token:
        return
    status, reply = rest_request(url, token, ["GET", manifest["prefix"] + "handle"])
    if status != 200 or not isinstance(reply, dict) or "result" not in reply:
        raise StandbyError("Standby HTTPS credentials could not read the handle counter")
    if digest(int(reply["result"] or 0)) != manifest["sections"]["handle_counter"]:
        raise StandbyError("Standby HTTPS returned a different handle counter")


def setup_code(config, standby_url, rest_token, prefix):
    settings = {
        "redisUrl": standby_url,
        "redisRestToken": rest_token,
        "prefix": prefix,
        "s3Endpoint": config["s3Endpoint"],
        "s3Region": config.get("s3Region") or "auto",
        "bucket": config["s3Bucket"],
        "accessKey": config["s3AccessKey"],
        "secretKey": config["s3SecretKey"],
        "fallbackBuckets": [name.strip() for name in config.get("s3FallbackBuckets", "").split(",") if name.strip()],
    }
    storage = urlsplit(settings["s3Endpoint"])
    if storage.scheme != "https" or not storage.hostname:
        raise StandbyError("The storage configuration must use HTTPS")
    if rest_token and not urlsplit(standby_url).hostname.endswith(".upstash.io"):
        raise StandbyError("Standby HTTPS fallback requires an Upstash endpoint")
    if not all(settings[key] for key in ("bucket", "accessKey", "secretKey")):
        raise StandbyError("Complete storage credentials are required for the replacement setup code")
    return base64.urlsafe_b64encode(json.dumps(settings, separators=(",", ":")).encode()).decode()


def main():
    cli = argparse.ArgumentParser(description=__doc__)
    actions = cli.add_subparsers(dest="action", required=True)
    capture = actions.add_parser("capture")
    capture.add_argument("--primary-config", type=Path, default=Path("local.properties"))
    capture.add_argument("--manifest", type=Path, required=True)
    for action in ("verify", "prepare"):
        cmd = actions.add_parser(action)
        cmd.add_argument("--manifest", type=Path, required=True)
        cmd.add_argument("--standby-config", type=Path, required=True)
        if action == "prepare":
            cmd.add_argument("--primary-config", type=Path, default=Path("local.properties"))
            cmd.add_argument("--output", type=Path, required=True)
            cmd.add_argument("--max-age-hours", type=float, default=24)
            cmd.add_argument("--primary-fenced", required=True, action="store_true",
                             help="Attest that the old primary cannot receive app writes on either transport")
    args = cli.parse_args()

    try:
        if args.action == "capture":
            config = read_config(args.primary_config)
            url = config["redisUrl"]
            source = endpoint(url)
            prefix = config.get("prefix") or "ap:"
            manifest = {
                "schema": 1,
                "source": source,
                "prefix": prefix,
                "captured_at": datetime.now(timezone.utc).isoformat(),
                "sections": inspect_url(url, prefix),
            }
            write_exclusive(args.manifest, json.dumps(manifest, indent=2) + "\n")
            print(f"Captured {len(manifest['sections'])} catalog fingerprints from {source}.")
            return

        manifest = json.loads(args.manifest.read_text())
        standby = read_config(args.standby_config)
        url = standby["redisUrl"]
        if args.action == "prepare":
            age = datetime.now(timezone.utc) - datetime.fromisoformat(manifest["captured_at"])
            if not 0 <= age.total_seconds() <= args.max_age_hours * 3600:
                raise StandbyError("Manifest age is outside the permitted recovery window")
            primary = read_config(args.primary_config)
            if endpoint(primary["redisUrl"]) != manifest["source"] or (primary.get("prefix") or "ap:") != manifest["prefix"]:
                raise StandbyError("Primary settings do not match the captured source")
        verified_sections = verify(manifest, url)
        rest_token = standby.get("redisRestToken", "")
        verify_standby_https(manifest, url, rest_token)
        print(f"Standby matches all {verified_sections} captured catalog fingerprints.")
        if args.action == "prepare":
            check_primary_fence(primary)
            code = setup_code(primary, url, rest_token, manifest["prefix"])
            write_exclusive(args.output, code + "\n")
            print("Created a private replacement setup code for coordinated device cutover.")
    except (ValueError, KeyError, OSError, RuntimeError) as error:
        # Redis command errors and filesystem errors can contain request data or paths.
        message = str(error) if isinstance(error, StandbyError) else type(error).__name__
        sys.exit(f"Standby check failed. {message}")


if __name__ == "__main__":
    main()
