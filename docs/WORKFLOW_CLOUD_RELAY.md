# Workflow cloud relay contract

OmniCode does not ship a hosted account service. If a team needs cross-device task recovery, deploy
a small HTTPS relay that treats the request body as opaque bytes.

## Endpoints

`POST /v1/workflows/{base64url-workflow-id}`

- `Authorization: Bearer <token>` is required.
- `Content-Type: application/octet-stream` and `X-OmniCode-Transfer-Version: 1` are required.
- Store at most 2 MiB of the body and return any 2xx status. Do not decrypt, index, or log it.

`GET /v1/workflows/{base64url-workflow-id}`

- Return the exact previously stored bytes with `Content-Type: application/octet-stream`.
- Reject bodies larger than 2 MiB and return 404 for an unknown id.

The client requires HTTPS; plain HTTP is accepted only for loopback development. The task package
already uses PBKDF2 + AES-GCM and contains a redacted textual checkpoint only. The relay must
provide authentication, rate limits, retention/deletion, access logging policy, and encrypted
storage appropriate for the deployment. The encryption passphrase is never sent to the relay.
