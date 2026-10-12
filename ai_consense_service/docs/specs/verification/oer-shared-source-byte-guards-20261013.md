# Shared fixed-source byte guards

2026-10-13, local delivery integration. Fixed retrieval now streams SHA-256 over each distinct selected original identified by the frozen manifest. Retrieval, publication checks, model-material projection and read-only parent retrieval require a unique descriptor, exact byte count and matching original digest. The selected corpus rows are also re-read and compared with their sealed row digests. Neither file size nor a restored modification timestamp substitutes for byte identity.

The final `FixedBaselineRetrievalClientTest` Surefire XML reports **8 tests, 0 failures/errors/skips**. Two new tests replace the authored original or selected corpus with equal-length bytes and restore the original timestamp; retrieval, current-source checks, model projection and read-only lookup reject the changed bytes. The fixture is authored text, not an original-quality PDF test. Evidence: external `fixed-original-byte-final.log` and the class XML/text in the delivery build directory.

The surrounding Advice/fixed-source selection ran **32 tests, 0 failures/errors/skips** before the final selected-row tightening; the final eight-case run covers that additional change. These overlapping runs are not summed. Project-upload byte guards are separately implemented through the existing storage service.

The first integrated web run recorded **117 cases, 116 pass, 1 fail** while the fixed panel was still waiting for WebCrypto hashing. The test now waits for the actual bounded observed state instead of assuming ten event-loop turns complete a native asynchronous digest. The final focused panel run passes all eight cases without changing the production hash guard or accepting a loading state. A full integrated rerun is still required after all implementation commits are combined.

No real model, source-quality, active Local05 storage or H800 acceptance is claimed by these tests.
