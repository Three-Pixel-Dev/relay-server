# Token encryption (AES-256-GCM)

Relay encrypts Gmail OAuth refresh/access tokens with Java built-in **AES-256-GCM**.  
There is **no Cloud KMS** dependency.

## Format

`enc:v{version}:{base64(12-byte IV || ciphertext || 128-bit auth tag)}`

- IV: 12 random bytes from `SecureRandom` per encryption
- Tag: 128-bit GCM authentication tag
- Key version stored in the ciphertext prefix and on `relay_gmail_mailbox.encryption_key_version`

## Configuration

| Env | Purpose |
|-----|---------|
| `RELAY_TOKEN_ENCRYPTION_KEY` | Base64-encoded **exactly 32 bytes** (AES-256) |
| `RELAY_TOKEN_ENCRYPTION_KEY_VERSION` | Current key version (default `1`) |
| `RELAY_TOKEN_ENCRYPTION_PREVIOUS_KEY` | Previous key (Base64 32 bytes) for decrypt during rotation |
| `RELAY_TOKEN_ENCRYPTION_PREVIOUS_KEY_VERSION` | Previous version id |

Generate a key:

```bash
openssl rand -base64 32
```

Never commit real keys. Never log decrypted tokens.

## Backup

1. Store the Base64 key material in your secret manager / sealed ops vault (or encrypted backup).
2. Record the key version next to the secret.
3. Keep at least one previous key until all mailboxes have been re-encrypted under the new version.

## Rotation

1. Generate a new 32-byte key; set it as `RELAY_TOKEN_ENCRYPTION_KEY` with a new `RELAY_TOKEN_ENCRYPTION_KEY_VERSION`.
2. Move the old key to `RELAY_TOKEN_ENCRYPTION_PREVIOUS_KEY` / `PREVIOUS_KEY_VERSION`.
3. Restart instances. New encrypts use the current key; decrypt still accepts previous version payloads.
4. Optionally re-save each mailbox (token refresh) so tokens rewrite under the new version.
5. After all rows use the new version, clear the previous key env vars.

## Failure behavior

Encryption/decryption failures throw and do not return partial plaintext. Wrong keys produce authentication-tag failures.
