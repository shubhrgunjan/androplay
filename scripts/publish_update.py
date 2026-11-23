#!/usr/bin/env python3
"""Publish an AndroPlay build as an in-app update.

Uploads the APK to the content bucket (releases/...) and announces it in Redis
(`<prefix>release` hash + `op=release` stream event), so every running device sees it
within seconds. Reads the backend settings from local.properties (androplay.* keys).

    python3 scripts/publish_update.py --notes "Slideshows + fixes"
"""
import argparse
import datetime
import hashlib
import hmac
import http.client
import re
import socket
import ssl
import sys
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def props():
    out = {}
    for line in (ROOT / "local.properties").read_text().splitlines():
        line = line.strip()
        if line.startswith("androplay.") and "=" in line:
            k, v = line.split("=", 1)
            out[k.removeprefix("androplay.").strip()] = v.strip()
    missing = [k for k in ("redisUrl", "s3Endpoint", "s3Bucket", "s3AccessKey", "s3SecretKey") if not out.get(k)]
    if missing:
        sys.exit(f"local.properties is missing androplay.{', androplay.'.join(missing)}")
    return out


def version():
    gradle = (ROOT / "app/build.gradle.kts").read_text()
    code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
    return code, name


def buckets(p):
    out = [p["s3Bucket"]]
    extra = p.get("s3FallbackBuckets", "")
    for b in extra.split(","):
        b = b.strip()
        if b and b not in out:
            out.append(b)
    return out


def s3_put(p, key, body, content_type):
    expected = hashlib.sha256(body).digest()
    for index, bucket in enumerate(buckets(p)):
        try:
            s3_request(p, bucket, key, "PUT", body, content_type)
            if s3_request(p, bucket, key, "GET") != expected:
                raise RuntimeError("Read-back checksum differs from the release APK")
            print(f"  uploaded and verified on {bucket}")
        except Exception as e:
            if index == 0:
                sys.exit(f"Primary release bucket failed verification: {e}")
            print(f"  replica was not verified on {bucket}: {e}")


def s3_request(p, bucket, key, method, body=b"", content_type="application/octet-stream"):
    endpoint = urllib.parse.urlparse(p["s3Endpoint"])
    if endpoint.scheme != "https" or not endpoint.hostname:
        raise ValueError("Release storage requires HTTPS")
    host = endpoint.netloc
    region = p.get("s3Region") or "auto"
    path = "/" + bucket + "/" + "/".join(urllib.parse.quote(s, safe="-_.~") for s in key.split("/"))
    now = datetime.datetime.now(datetime.timezone.utc)
    amz_date, day = now.strftime("%Y%m%dT%H%M%SZ"), now.strftime("%Y%m%d")
    payload_hash = hashlib.sha256(body).hexdigest()
    canonical = "\n".join([
        method, path, "",
        f"host:{host}\nx-amz-content-sha256:{payload_hash}\nx-amz-date:{amz_date}\n",
        "host;x-amz-content-sha256;x-amz-date", payload_hash,
    ])
    scope = f"{day}/{region}/s3/aws4_request"
    to_sign = f"AWS4-HMAC-SHA256\n{amz_date}\n{scope}\n{hashlib.sha256(canonical.encode()).hexdigest()}"
    k = ("AWS4" + p["s3SecretKey"]).encode()
    for part in (day, region, "s3", "aws4_request"):
        k = hmac.new(k, part.encode(), hashlib.sha256).digest()
    signature = hmac.new(k, to_sign.encode(), hashlib.sha256).hexdigest()
    headers = {
        "Host": host,
        "x-amz-date": amz_date,
        "x-amz-content-sha256": payload_hash,
        "Content-Type": content_type,
        "Authorization": f"AWS4-HMAC-SHA256 Credential={p['s3AccessKey']}/{scope}, "
                         f"SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature={signature}",
    }
    conn = http.client.HTTPSConnection(host, timeout=120)
    try:
        conn.request(method, path, body=body, headers=headers)
        response = conn.getresponse()
        if response.status >= 300:
            raise RuntimeError(f"HTTP {response.status}")
        if method == "GET":
            return hashlib.file_digest(response, "sha256").digest()
        response.read()
    finally:
        conn.close()


class Redis:
    def __init__(self, url):
        u = urllib.parse.urlparse(url.strip())
        if u.scheme != "rediss":
            raise ValueError("Release Redis requires TLS")
        raw = socket.create_connection((u.hostname, u.port or 6379), timeout=15)
        self.sock = ssl.create_default_context().wrap_socket(raw, server_hostname=u.hostname)
        self.buf = self.sock.makefile("rb")
        if u.password:
            self.call("AUTH", *( [urllib.parse.unquote(u.username)] if u.username else []), urllib.parse.unquote(u.password))

    def call(self, *args):
        out = b"*%d\r\n" % len(args)
        for a in args:
            a = str(a).encode()
            out += b"$%d\r\n%s\r\n" % (len(a), a)
        self.sock.sendall(out)
        return self._read()

    def _read(self):
        line = self.buf.readline().rstrip(b"\r\n")
        t, rest = line[:1], line[1:]
        if t == b"-":
            raise RuntimeError(rest.decode())
        if t in (b"+", b":"):
            return rest.decode()
        if t == b"$":
            n = int(rest)
            return None if n < 0 else self.buf.read(n + 2)[:-2].decode()
        if t == b"*":
            n = int(rest)
            return None if n < 0 else [self._read() for _ in range(n)]
        raise RuntimeError(f"Unexpected reply {line!r}")


def verify_no_embedded_credentials(p, apk):
    import zipfile

    redis = urllib.parse.urlparse(p["redisUrl"])
    values = [urllib.parse.unquote(redis.password or ""), p["s3AccessKey"], p["s3SecretKey"]]
    for url in p.get("redisFallbackUrls", "").split(","):
        values.append(urllib.parse.unquote(urllib.parse.urlparse(url.strip()).password or ""))
    needles = [value.encode() for value in values if len(value) >= 8]
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if name.endswith(".dex"):
                dex = archive.read(name)
                if any(value in dex for value in needles):
                    sys.exit("APK contains backend credentials. Build without androplay.embedBackend.")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--apk", default=str(ROOT / "app/build/outputs/apk/release/app-release.apk"))
    ap.add_argument("--notes", default="")
    args = ap.parse_args()

    p = props()
    code, name = version()
    apk = Path(args.apk)
    data = apk.read_bytes()
    verify_no_embedded_credentials(p, apk)
    sha = hashlib.sha256(data).hexdigest()
    key = f"releases/androplay-{name}-{code}.apk"
    prefix = p.get("prefix") or "ap:"

    print(f"Uploading v{name} ({code}), {len(data) / 1e6:.1f} MB -> {key}")
    s3_put(p, key, data, "application/vnd.android.package-archive")

    # Independent databases are not replicas. Only announce to the authoritative Redis.
    urls = [p["redisUrl"]]
    announced = 0
    for i, url in enumerate(urls):
        label = "primary" if i == 0 else f"fallback {i}"
        try:
            r = Redis(url)
            r.call("HSET", f"{prefix}release", "versionCode", code, "versionName", name, "objectKey", key,
                   "sha256", sha, "size", len(data), "notes", args.notes,
                   "publishedAt", int(datetime.datetime.now().timestamp() * 1000))
            r.call("XADD", f"{prefix}events", "MAXLEN", "~", "10000", "*", "op", "release", "id", code)
            announced += 1
            print(f"  announced on Redis {label}")
        except Exception as e:
            print(f"  Redis {label} failed: {e}")
    if not announced:
        sys.exit("Could not announce the release on any Redis")
    print(f"Published v{name}. Devices update within seconds (boards wait until idle).")


if __name__ == "__main__":
    main()
