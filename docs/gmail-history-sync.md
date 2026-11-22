# Gmail History sync (no Pub/Sub)

Relay does **not** use Gmail Watch or Google Cloud Pub/Sub.

## Design

- Spring `@Scheduled` job every `relay.gmail.sync-interval-ms` (default **60_000** ms).
- Toggle with `relay.gmail.scheduling-enabled` / `GMAIL_SCHEDULING_ENABLED` (default **true**).
- Each connected `relay_gmail_mailbox` syncs independently.
- Per-mailbox lease (`sync_lease_owner` / `sync_lease_until`) prevents overlapping jobs across instances.
- `history_id` is persisted on the mailbox and updated **only after** all fetched changes for that tick are processed.
- Idempotency: `relay_gmail_processed_message` unique on `(mailbox_id, gmail_message_id)`.
- Rate limits: exponential backoff in `GoogleGmailApiClient`.
- Expired history cursor (HTTP 404): recover by fetching known outreach threads, then baseline a fresh `historyId` from `users.getProfile`.
- Initial connect: baseline current `historyId` (no Watch registration). Future changes are never skipped once baselined.

## Reply matching

- Match by Gmail **thread id** against outreach messages for that mailbox.
- Validate From = prospect email and To contains mailbox email.
- Strip quoted history before classification.
- Auto-replies / bounces / unsubscribe do **not** cancel as human interest; bounces/unsubscribes suppress and cancel follow-ups.
- Human replies cancel pending follow-ups inside a DB transaction.

## OAuth statuses

`CONNECTED` | `REAUTH_REQUIRED` | `DISCONNECTED` | `ERROR`

Revoked/expired refresh tokens mark the mailbox `REAUTH_REQUIRED` without deleting the row.
