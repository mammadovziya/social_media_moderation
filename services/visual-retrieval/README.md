# Visual retrieval service

This internal service retrieves geometrically related reference images. It is deliberately **candidate-only**: no ORB, LSH, or homography result can directly allow or block content. The moderation gateway must send returned candidates to the configured adjudication policy.

The service has no database client and makes no OpenAI or other network calls. Compilation returns a bounded descriptor payload to its caller; refresh only creates a process-local immutable index cache.

This is bounded retrieval infrastructure, not proof that an ORB policy meets release quality or that the surrounding deployment controls are complete. Keep it candidate-only and in shadow/feature-gated operation until the activated `UNMASKED` profile passes the repository's held-out retrieval gates. `BACKGROUND` is an unsupported experimental profile after the local masking ablation failed; it must not be activated unless it receives a separate governed evaluation and approval. Analyzer errors, timeouts, missing revisions, and `INSUFFICIENT_FEATURES` must route to the configured fail-closed/Terra path; they must never be interpreted as no violation.

## Contract

All internal endpoints require `X-Internal-Token` when `VISUAL_RETRIEVAL_INTERNAL_TOKEN` is configured, even if local unauthenticated mode was also selected. The token must contain 32 to 512 URL-safe `A-Z a-z 0-9 . _ ~ -` characters. Authentication is secure by default: without a token, readiness and internal calls fail. Local Compose may explicitly set `VISUAL_RETRIEVAL_ALLOW_UNAUTHENTICATED=true` only when no token is configured.

`Content-Length` is required. Image parts must have an explicit `image/jpeg`, `image/png`, `image/gif`, or `image/webp` content type. GIF and WebP inputs must be static, single-frame images.

### `POST /internal/v1/descriptors/compile`

Multipart fields:

- `image`: source image. It is decoded only for this request and is never retained.
- `descriptorVersion`: exactly `opencv-orb-4.12-v1`.
- `channel`: `BACKGROUND` or `UNMASKED`.
- `exclusionBoxes`: JSON array of normalized `[x, y, width, height]` boxes. Coordinates and dimensions are fractions of the oriented source image. `BACKGROUND` requires one or more boxes; `UNMASKED` requires `[]`.

The compiler applies EXIF orientation, composites transparency on white, converts to grayscale, and downscales with OpenCV area interpolation when necessary. `BACKGROUND` expands each exclusion box by 64 working-image pixels—larger than the maximum ORB descriptor support radius in this profile—and supplies the resulting binary mask to ORB. The boxes and mask bytes are discarded after extraction.

The JSON response contains:

```json
{
  "schemaVersion": "orb-descriptor-payload/v1",
  "channel": "BACKGROUND",
  "algorithm": {
    "name": "ORB",
    "algorithmVersion": "opencv-orb-4.12-v1",
    "implementation": "OpenCV",
    "implementationVersion": "4.12.0",
    "canonicalizationVersion": "pillow-exif-rgba-white-gray-cv-area/v1",
    "descriptorType": "binary-uint8",
    "descriptorBytes": 32,
    "maxFeatures": 1800
  },
  "workingWidth": 720,
  "workingHeight": 480,
  "keypointCount": 1234,
  "keypoints": [[0.125, 0.25]],
  "descriptorsBase64": "...",
  "sourceSha256": "...",
  "descriptorSha256": "...",
  "exclusionMaskVersion": "normalized-box-padding-64px/v1",
  "exclusionMaskSha256": "...",
  "usable": true
}
```

`keypoints` contains exactly `keypointCount` `[x, y]` pairs normalized to `[0,1]`. The flat base64 bytes contain exactly `keypointCount * 32` bytes in the same order. An unmasked payload has null exclusion-mask fields. A payload with fewer than 16 keypoints is returned with `usable=false` and cannot be loaded as an active reference.

### `POST /internal/v1/indexes/refresh`

JSON body:

```json
{
  "revision": "moderation-references-1042",
  "references": [
    {"referenceId": "asset-version-17", "descriptor": {}}
  ]
}
```

The caller supplies the complete immutable active descriptor snapshot. An empty `references` list is a valid ready snapshot. A reference ID may occur once per channel. Every descriptor profile, count, normalized point, base64 length, descriptor digest, and mask profile is revalidated before an index is made visible.

Refresh is atomic and deterministic with respect to payload content:

- Repeating the same revision and content is idempotent and returns `created=false` with the same `snapshotDigest`.
- Reusing a revision for different content returns HTTP 409 `revision_conflict`.
- The cache retains at most three revisions using bounded LRU eviction. An in-flight query keeps its immutable snapshot object even if the cache evicts that revision.
- `BACKGROUND` and `UNMASKED` build separate LSH indexes. Descriptors are never compared across channels.

The cache is intentionally process-local. Run one Uvicorn worker per instance and refresh each replica before routing queries for a new revision.

### `POST /internal/v1/query`

Multipart fields:

- `image`: query image with an explicit image content type.
- `revision`: exact immutable reference revision.
- `topK`: integer from 1 through 5. The current version emits at most one candidate.
- `descriptorVersion`: exactly `opencv-orb-4.12-v1`.
- `channel`: `BACKGROUND` or `UNMASKED`.
- `exclusionBoxes`: the same transient `[x, y, width, height]` contract as compilation.

A missing or evicted revision returns HTTP 409 `reference_revision_not_loaded`; it never becomes an empty successful result. Query responses always contain `candidateOnly=true` and `authoritative=false`. Each candidate includes the matched channel, LSH votes, Lowe-ratio match count, RANSAC inlier count/ratio, and median Hamming distance.

Every query response identifies both governed profiles: `algorithmVersion=opencv-orb-4.12-v1` for descriptor extraction, matching, and ranking, and `candidateSelectionVersion=orb-homography-specificity-v1` for the final abstention/emission rule. Callers must validate both values before using candidate evidence.

`INSUFFICIENT_FEATURES` has `complete=false`, so callers must treat it as unavailable evidence rather than a clean image. `NO_GEOMETRIC_CANDIDATES` has `complete=true` only when extraction, exact-revision lookup, and the bounded search all completed. It can also mean that valid ORB matches were ambiguous under the specificity rule; it is never proof that the current content is safe.

After the existing ratio-match, homography-inlier, and inlier-ratio validity gates, all valid geometrically verified matches in the bounded LSH shortlist are deterministically ranked. Candidate-selection profile `orb-homography-specificity-v1` computes `top homography inliers - runner-up homography inliers`, using zero when no runner-up exists. It emits exactly the rank-one match only when that lead is at least 12; otherwise it emits no ORB candidate. The full ranked shortlist is used for this calculation before applying `topK`, so requesting `topK=1` cannot conceal an ambiguous runner-up within that bounded shortlist. This is not an exhaustive comparison against every reference in the snapshot. `distinctiveGeometry` reports whether this versioned emission gate passed, and `distinctiveInlierLead` reports the calculated margin. Both remain diagnostic candidate evidence, not a moderation decision.

### Health

- `GET /health`: liveness; it does not claim the index or authentication is ready.
- `GET /ready`: ready only when the pinned OpenCV version is active and internal authentication is configured (or local unauthenticated mode was explicitly selected). An empty loaded snapshot is valid; `loadedRevisions` reports cache state.

## Versioned algorithms

Descriptor, matching, and ranking profile `opencv-orb-4.12-v1` is fixed to:

- OpenCV `4.12.0`, ORB `maxFeatures=1800`, scale factor `1.2`, 8 levels, edge/patch 31, FAST threshold 20.
- Separate `BACKGROUND` and `UNMASKED` channels.
- OpenCV FLANN binary LSH: 12 tables, 20-bit keys, multi-probe level 2; at most 20 references proceed to reranking.
- Exact Hamming reranking with Lowe ratio `0.72`.
- Homography RANSAC with 4-pixel reprojection threshold, 1,000 iterations, and 0.995 confidence.
- Candidate inclusion requires at least 8 unique ratio matches, 6 inliers, and 0.25 inlier ratio.
- Deterministic final ordering: inliers, inlier ratio, match count, median Hamming distance, then reference ID.

Candidate-selection profile `orb-homography-specificity-v1` is fixed to returning only the rank-one valid match when its homography-inlier lead over the runner-up (zero if absent) is at least 12; otherwise it returns none.

Any change to canonicalization, masks, descriptors, matching parameters, candidate validity, candidate ordering, or OpenCV requires a new descriptor/matching algorithm version and recompiled reference snapshot. Any change to the abstention/emission calculation or threshold requires a new candidate-selection version. Both versions must be propagated, validated, evaluated, and audited end to end.

## Hard limits

| Resource | Limit |
|---|---:|
| Compressed image | 8 MiB |
| Image multipart request | 9 MiB |
| Refresh JSON request | 64 MiB |
| Decoded image | 16,777,216 pixels |
| Source dimension | 32–8,192 pixels per side |
| ORB working dimension | 2,048 pixels on longest side |
| Keypoints/descriptors per payload | 1,800 |
| Exclusion boxes | 256 / 32 KiB JSON / at most 80% union coverage |
| Descriptor entries per snapshot | 256 |
| Total descriptors per snapshot | 250,000 |
| Cached revisions | 3 |
| LSH rerank pool | 20 references |
| Returned candidates | 1 |
| Concurrent CPU jobs | 2 |
| Compile / query / refresh CPU deadline | 10 s / 10 s / 25 s |

Request deadlines are enforced around bounded worker jobs. OpenCV calls are also bounded by pixels, features, candidate count, RANSAC iterations, and single-threaded OpenCV execution.

## Privacy and deployment

The service never performs OCR. Source image bytes, decoded pixels, exclusion boxes, and masks are request-local and are not stored in the revision cache or application logs. Only descriptors, normalized keypoints, IDs, versions, and cryptographic digests are cached. Uvicorn access logging is disabled in the image, and errors never echo image, descriptor, or request-body content.

Install and test:

```sh
python3.12 -m venv .venv
.venv/bin/pip install --requirement requirements-dev.txt
.venv/bin/python -m pytest
```

Build and run with a read-only root filesystem:

```sh
docker build -t moderation-visual-retrieval .
docker run --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=32m \
  -e VISUAL_RETRIEVAL_INTERNAL_TOKEN='replace-with-at-least-32-random-characters' \
  -p 8000:8000 moderation-visual-retrieval
```

The `/tmp` tmpfs permits the multipart parser to spool bounded uploads without making the container root filesystem writable. It is ephemeral and must not be backed by a persistent volume.
