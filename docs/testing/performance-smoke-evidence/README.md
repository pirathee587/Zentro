# #176 execution evidence

Base: `144f0cc121cd36ab3a92ffded5f7f810963bd29b`.
Final test/fix source snapshot: `4eec084a22bc39cae901194ad5898c36d27fa61c`.
Date: 2026-11-10, Asia/Colombo. Merged SHA: **to be recorded after merge**.

* `backend-build.txt`: actual full `cd backend && mvn --batch-mode test package`, 2073 tests passed, zero skipped among executed modules. Opt-in E2E/perf module intentionally not run by the default configuration (the skipped lifecycle messages are preserved).
* `focused-tests.txt`: final S4/S5 selection, 61 passed, zero skips.
* `e2e-regression.txt`: reused merchant fixture, 2 passed, zero skips.
* `run-1`, `run-2`: final identical-setting full perf executions; JUnit **failed** on the original sanity bounds. Raw samples retain all measured responses, expected 404/504 classes, bytes and host CPU/memory snapshots. Counts are 2600 and 2500; run 2 stopped booking after five clients. No unexpected status/integrity error occurred in either measured set.
* `failure-supplement`: 800 measured requests and 50 background controls, passed; dedicated actively concurrent timeout isolation before the final runs.
* `lists-supplement`: bounded growth and contrasting-owner/repeat-query validation, passed before the final runs.

Profile command from `backend/`:

```sh
mvn --batch-mode -Pperf -pl merchant-order-webhook-e2e -am \
  -Dtest=PerformanceSmokeIT -Dsurefire.failIfNoSpecifiedTests=false verify
```

The two final runs used exactly this configuration (invoked equivalently from the repository root with `-f backend/pom.xml`); settings hashes are retained in `execution.txt`. No CI job or external load-testing dependency was added. GC summaries contain only this run's current log per service; gateway was restarted for the slow-upstream fixture, so its earlier process is not included. Service logs, credentials and tokens are deliberately absent.

The 22→3 S4 prepared-statement comparison is a service-integration diagnostic, not gateway throughput: same 21-row seed/page size 20, 41.764 ms before and 19.734 ms after in the initial focused comparison. Final focused diagnostic: 3 statements / 18.784 ms. Elapsed values are single-request observations; query reduction is the robust evidence. All reference HTTP scenarios go through the real fixture gateway. Owning-database diagnostics use only the owning login. No schema/index migration was added.
