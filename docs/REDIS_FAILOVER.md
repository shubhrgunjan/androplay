# Redis standby recovery

AndroPlay uses one writable catalog. TCP and HTTPS are alternate connections to that catalog. A second Redis database becomes writable for AndroPlay only after a coordinated promotion. Keep a provider backup in a recovery location appropriate for the outage you want to survive.

## Prepare and verify a standby

1. Create a separate, dedicated standby in Upstash and keep its application credentials private. Enable provider backups on the primary. Obtain distinct native and REST credentials for the standby so both transports target the same database.
2. Pause catalog changes and release publishing. Capture the primary's catalog manifest, then make the Upstash backup while catalog changes are paused. Keep the backup ID and manifest together in private storage. The manifest fingerprints the catalog records and ordering, handle counter, tombstones, slideshows, release record, and latest stream event. The provider backup contains the recoverable data.
3. Restore the chosen backup into the dedicated standby. Upstash replaces existing data in the restore target. Recreate the standby's access policies as needed.
4. Verify it before using it. A difference in any of the six fingerprints blocks promotion. When a REST token is provided, the verifier also compares the standby's handle counter over HTTPS. A manifest older than the backup, or a catalog write between capture and backup, requires capturing a matching pair.

Run from the repo root. Both configuration files use the `androplay.*` property format. Store them outside source control. The standby file needs `androplay.redisUrl` and, for HTTPS support, `androplay.redisRestToken`.

```bash
python3 -B scripts/redis_standby.py capture \
  --primary-config /private/primary.properties \
  --manifest /private/catalog-manifest.json

# Restore the matching backup into the dedicated standby in the Upstash console.
python3 -B scripts/redis_standby.py verify \
  --manifest /private/catalog-manifest.json \
  --standby-config /private/standby.properties
```

## Promote

1. Keep cloud writes paused. Fence the old primary at the provider so that every deployed device and the release publisher lose write access on both transports. Disable or revoke their old credentials. Include earlier app versions and any old fallback they still have configured. Loss of connectivity alone can clear while the old database remains writable.
2. Run `prepare`. It rechecks the standby, enforces the recovery window, and rejects a reachable primary that accepts the old credentials supplied in the primary file. If the primary is unreachable, the command relies on the operator's attestation that the provider-side fence is in place. The default recovery window is 24 hours. Use `--max-age-hours` only after reviewing the changes the backup may have missed.
3. Apply the generated code privately on the updated board and admin phones via **Settings**. The code contains credentials. Use a release built without embedded backend defaults. Older clients must be upgraded or kept fenced during cutover. Update the release publisher's Redis URL to match and leave publishing paused until the smoke test passes.
4. Confirm the board heartbeat and catalog, then play a known item and check its acknowledgment and Activity logs. Confirm the HTTPS transport using the standby's REST token. Resume uploads and release publishing once all clients point to the verified standby. To fail back, repeat the process from the new primary with a fresh backup and fence the promoted endpoint.

```bash
python3 -B scripts/redis_standby.py prepare \
  --primary-config /private/primary.properties \
  --manifest /private/catalog-manifest.json \
  --standby-config /private/standby.properties \
  --primary-fenced \
  --output /private/standby-setup.code
```

The primary config supplies the old credentials checked during fencing and the storage settings retained during cutover. The manifest and setup code are created with owner-only permissions and require new filenames. Keep the setup code in your secret store.
