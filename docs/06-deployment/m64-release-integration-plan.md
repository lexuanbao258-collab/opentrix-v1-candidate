<!--
SPDX-FileCopyrightText: 2026 TRICORE contributors
SPDX-License-Identifier: Apache-2.0
-->

# M6.4 candidate release integration plan

1. Record the accepted Platform, ESM and Procurement commits and confirm the runtime diff remains anchored at
   `f401b6e79930a1386e71e92a91ae6a909cfa1e59`. Confirm the owner and the verified destination of each repository.
   Procurement remains local until its owner approves a remote.
2. Review the candidate branch against the current default branch. The default still represents the upstream
   baseline. Decide explicitly whether to use a separate candidate branch or an approved default-branch update.
   Keep the existing upstream history and distinguish candidate work from upstream releases.
3. After the separate M6.3 onboarding work is accepted, verify the verified candidate refs and documentation links
   from fresh checkouts. Repeat the release-critical smoke checks, migration checks, secret scan and source license
   verification. Report CI by exact SHA and distinguish local verification from hosted CI.
4. Review the release payload and rollback. The intended candidate payload is source-only. Review image licenses and
   distribution obligations separately if packaging images later. Retain prior refs and require a backup and a verified
   restore plan for any database migration.
5. Obtain explicit owner approval for any merge, default-branch change, release tag or publication. Record the
   approved targets and SHAs before executing each separate action.

This plan does not authorize publication. M6.1-FIX ends with local verification and commits.
