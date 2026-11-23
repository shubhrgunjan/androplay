import base64
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import redis_standby as standby


class StandbyTests(unittest.TestCase):
    def test_catalog_mismatch_reports_only_changed_sections(self):
        manifest = {"schema": 1, "prefix": "ap:", "source": "a.upstash.io:6379",
                    "sections": {"catalog": "original", "release": "same"}}
        with patch.object(standby, "inspect_url", return_value={"catalog": "different", "release": "same"}):
            with self.assertRaisesRegex(ValueError, "captured source in catalog$"):
                standby.verify(manifest, "rediss://user:example@b.upstash.io")

    def test_matching_standby_uses_the_source_prefix(self):
        manifest = {"schema": 1, "prefix": "lab:", "source": "a.upstash.io:6379",
                    "sections": {"catalog": "same"}}
        with patch.object(standby, "inspect_url", return_value={"catalog": "same"}) as inspect:
            url = "rediss://user:example@b.upstash.io"
            self.assertEqual(standby.verify(manifest, url), 1)
            inspect.assert_called_once_with(url, "lab:")

    def test_primary_host_is_rejected_as_standby(self):
        manifest = {"schema": 1, "source": "a.upstash.io:6379"}
        with self.assertRaisesRegex(ValueError, "must differ"):
            standby.verify(manifest, "rediss://other:example@A.upstash.io:6379/0")

    def test_reachable_primary_with_old_credentials_blocks_cutover(self):
        with patch.object(standby, "Redis"):
            with self.assertRaisesRegex(ValueError, "still accepts"):
                standby.check_primary_fence({"redisUrl": "rediss://user:example@a.upstash.io"})

    def test_standby_https_must_match_captured_counter(self):
        manifest = {"prefix": "lab:", "sections": {"handle_counter": standby.digest(18)}}
        url = "rediss://user:example@b.upstash.io"
        with patch.object(standby, "rest_request", return_value=(200, {"result": "18"})) as request:
            standby.verify_standby_https(manifest, url, "rest-token")
            request.assert_called_once_with(url, "rest-token", ["GET", "lab:handle"])
        with patch.object(standby, "rest_request", return_value=(200, {"result": "19"})):
            with self.assertRaisesRegex(ValueError, "different handle counter"):
                standby.verify_standby_https(manifest, url, "rest-token")

    def test_setup_code_uses_only_the_verified_standby_and_is_private(self):
        config = {"s3Endpoint": "https://storage.example", "s3Bucket": "media",
                  "s3AccessKey": "access", "s3SecretKey": "secret"}
        standby_url = "rediss://user:example@b.upstash.io"
        code = standby.setup_code(config, standby_url, "rest-token", "lab:")
        decoded = json.loads(base64.urlsafe_b64decode(code))
        self.assertEqual(decoded["redisUrl"], standby_url)
        self.assertEqual(decoded["redisRestToken"], "rest-token")
        self.assertEqual(decoded["prefix"], "lab:")
        self.assertNotIn("fallbackRedisUrls", decoded)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "setup.code"
            standby.write_exclusive(path, code)
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)
            with self.assertRaises(FileExistsError):
                standby.write_exclusive(path, "replacement")


if __name__ == "__main__":
    unittest.main()
