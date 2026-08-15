---
name: nhentai-api
description: Look up nhentai API v2 endpoints, request/response schemas, auth levels, and rate limits. Use whenever working on network code, API models, authentication, or anything under com.maxwai.nclientv3.api - and before guessing an endpoint's shape, parameters, or whether it needs credentials.
---

# nhentai API v2 reference

The full OpenAPI spec is vendored at `docs/openapi.json` (~190KB, 105 operations).
**Do not read that file directly** - it will flood the context. Query it instead:

```bash
python scripts/apiref.py find <terms>          # search operations (AND across terms)
python scripts/apiref.py list --auth public    # list operations by auth level
python scripts/apiref.py list --path galleries # list operations by path
python scripts/apiref.py show GET /api/v2/galleries   # full detail, refs resolved
python scripts/apiref.py schema Gallery        # render a component schema
python scripts/apiref.py refresh               # re-download from nhentai.net
```

`show` prints the auth level, rate limits, parameters, request body, and response
schemas with `$ref`s already resolved. Use `--depth N` to go deeper or shallower
than the default 3.

## Things that are easy to get wrong

**Auth level is in the operation's `description` prose, not in `security`.** FastAPI
emits a `security` block for every operation, including ones using optional
(`auto_error=False`) auth, so `security` tells you nothing. The declared `401`
response is not a reliable signal either. `apiref.py` reads the `**Auth:**` line for
you - trust that, or probe with curl.

Six auth levels appear: Public (no auth), Public (optional, for personalization),
User Token or API Key, User Token required, Staff Token required, Superuser Token
required.

**Reads are public.** Galleries, search, popular, random, gallery detail, comments,
related, tagged, and tags all work anonymously.

**But send credentials anyway when available** - auth roughly doubles the per-IP
rate limit on every read path the app uses (`/search` 10 to 20/min, `/galleries` 15
to 30/min, `/galleries/{id}` 20 to 45/min). Every read declares `429`; handle it.

**Two credential forms**, both in the `Authorization` header: `Key <api_key>` or
`User <token>`. Login returns an access + refresh token pair, and requires a
proof-of-work and captcha solution (`/api/v2/pow`, `/api/v2/captcha`).

**CDN hosts are dynamic.** `GET /api/v2/config` returns `image_servers` and
`thumb_servers`; do not hardcode image hostnames.

**Set a descriptive User-Agent** of the form `AppName/version (project URL)`, as the
API docs request. See `scripts/update_tags.py` for the existing convention.
