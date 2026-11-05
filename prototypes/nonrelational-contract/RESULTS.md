# Testing the non-relational store design

PROTOTYPE, throwaway. This tests `docs/non-relational-stores.md` against classes of store. It does not belong on master.

## Verdict

The compare-and-set design is sound on one condition the doc never states: the store must make the version check, the change and the audit entry land as one atomic, durable step. Stores that can do this passed every safety check. Stores that can't produced cycles or lost writes, or left versions with no audit entry.

The writer loop as written is not safe under lost responses. It needs the write-ID check.

## What was tested

`ContractHarness.java` runs the doc's writer loop and pure rules against in-memory models. Each model implements one stated guarantee. Results show what follows from that guarantee, not how a named product behaves.

Real stores did not run. The git section is the exception: it uses real git, the real export format and the real `Evaluator`.

The full harness ran twice and every verdict matched. Raw output is in `run-1.txt` and `run-2.txt`; ranges below cover both runs. A control store with no compare failed as it should, so the checks can detect these failures.

## Results by store class

The product examples come from general knowledge. Check each one against its docs before relying on it.

| Store class | Examples to verify | Opposite-prerequisite race | Other results |
| --- | --- | --- | --- |
| Multi-key transaction with snapshot reads | etcd, Consul, ZooKeeper | Pass | All safety checks pass |
| Single-key CAS, one document with audit inside | MongoDB, CouchDB, a Cassandra partition | Pass | Safe, but the document fills up |
| Single-key CAS, audit in a separate key | Same, used this way | Not run | 9 to 13 versions with no audit entry when clients crash |
| Put-if-absent log | S3, GCS, Azure Blob | Pass | Safe unless writers can delete |
| Atomic commit, no snapshot reads | DynamoDB, MongoDB outside a transaction | Pass, 60 races | 183 to 190 of 1,087 reads saw half a two-flag write |
| Single-key CAS, one key per flag | Redis Cluster across slots, Cassandra across partitions, Bigtable | Fail, both won 10 to 19 of 150 | 8 to 23 versions with no audit entry |
| Last write wins, one key per flag | Multi-master or eventually consistent modes | Fail, both won 112 to 124 of 150, cycle | |
| Last write wins, one document | Same | Fail, 130 to 134 acknowledged writes lost | |
| Asynchronous replication with failover | Redis with a replica, MongoDB with w:1 | Not run | 1 to 2 acknowledged writes lost, version numbers reused |
| Synchronous or majority replication | Control | Not run | Pass |

The one-key-per-flag layout fails on single-key CAS even without a crash. The guard moves first and the flag follows, so a second writer can pass the guard while validating against a graph that lacks the first change.

## Lost responses

5% of commits lost their response. The write then landed, didn't land, landed late, or came back as a conflict after a resend.

| Writer | Writes audited twice | Reported rejected, but applied | Reported failed, but applied |
| --- | --- | --- | --- |
| Doc's loop | 2 to 5 | 2 to 5 | 7 to 15 |
| Also retries timeouts | 7 to 10 | 9 to 12 | 0 to 2 |
| Write-ID check before every retry | 0 | 0 | 0 |

This covers the transaction and log classes only. The write-ID check passed both runs, including writes that landed late.

## Reads

- **Follower reads.** Writers stay safe because the compare catches stale reads. Readers are not safe: 481 to 510 reads went back in time, so a flag someone switched off could look on again until a later read.
- **Version log deletes.** Hiding the newest object turned a kill switch back on, and the next write reused its version number.
- **Double read (meta, scan, meta).** With a 50 ms scan, reads took about 1.1 attempts at one write every 500 ms and 2.3 at every 100 ms. At every 25 ms, all 30 reads failed after 20 attempts. The doc sets no limit on these retries.
- **Single-flag writes on no-snapshot reads.** No invalid graphs appeared, but that rests on only about 400 reads, so it is weak evidence.

## Limits (arithmetic)

These assume the doc's 200-byte flag and a 500-byte audit entry.

- **Embedded audit:** a 400 KB document with 500 flags fills after 619 writes. A 16 MB document with 2,000 flags lasts 32,754.
- **Commit size:** passing the whole graph needs 102 operations at 100 flags, over a 64-operation limit. Passing only the change needs 3.
- **Version log, finding the newest:** 101 list requests at 100,000 versions with ascending names, 1 with inverted names.
- **Version log, storage:** 2,000 flags over 100,000 versions is about 38 GB if each object holds the whole graph.

## Git file store

Tested with real git and the real `Evaluator`.

- Two PRs, each valid alone, merged cleanly into a cycle. This happened for adjacent flags and for flags far apart.
- A running pod kept the old graph. A fresh pod's first load threw `InvalidGraphException`.
- `git log` showed whatever author the client typed.

## Retry limits

Rough only. Throughput stayed near 64 writes/s for both the 1 ms and 10 ms windows, which suggests the Windows timer is limiting the pauses. Read the give-up rates as a trend and do not read the throughput as a store limit.

| Writers | Gave up at 3 attempts | Gave up at 10 attempts |
| --- | --- | --- |
| 2 | 8 to 12% | 0 to 3% |
| 8 | 45 to 49% | 9 to 19% |
| 32 | 81 to 83% | 49 to 51% |

Giving up is safe, because the write is rejected, but a limit of 3 would be noisy for any concurrent automation.

## Changes this suggests for the doc

1. Add store requirements: an atomic step covering version, change and audit entry; acknowledged writes that survive failover; and reads that don't go backwards.
2. State that one key per flag needs multi-key transactions. Single-key CAS stores must use one document or a log.
3. Add the write-ID check to the contract and the test suite.
4. Commit a change, not the whole graph.
5. Limit the double read, and name a fallback.
6. Deny delete to version log writers.
7. Correct the git store's "no concurrency problem" and "no attribution problem" claims, and require a CI graph check.

## Not tested

- Any real store other than git. Real behaviour under network partitions needs real clusters and a Jepsen-style test.
- Permissions: whether each store can make audit append-only or enforce the actor.
- Eviction and TTL, which can drop keys in cache-like stores.

## Run it

From the repo root:

```
javac -d target-proto flagkit-core/src/main/java/dev/flagkit/*.java
java -cp target-proto prototypes/nonrelational-contract/ContractHarness.java
```
