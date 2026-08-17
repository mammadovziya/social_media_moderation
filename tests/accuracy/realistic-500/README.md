# Realistic 500-case candidate benchmark

This directory contains 500 synthetic, human-style moderation cases drafted for
native-speaker review. It extends the small contract suite in
`tests/accuracy/text-cases.jsonl`; it does not replace that suite.

## Composition

| File | Language | Posts | Comments | Usernames | Total |
| --- | --- | ---: | ---: | ---: | ---: |
| `az-125.jsonl` | Azerbaijani | 65 | 40 | 20 | 125 |
| `en-125.jsonl` | English | 65 | 40 | 20 | 125 |
| `ru-125.jsonl` | Russian | 65 | 40 | 20 | 125 |
| `tr-125.jsonl` | Turkish | 65 | 40 | 20 | 125 |
| **Total** |  | **260** | **160** | **80** | **500** |

The set is a coverage-oriented challenge benchmark, not a sample of production
prevalence. It deliberately includes more violations, ambiguity, hard negatives,
and adversarial wording than ordinary traffic so every policy axis is exercised.
All identifiers and situations are synthetic.

## Important status

The labels are **candidate labels**, not gold truth. Model-authored wording and
labels cannot be used to prove model accuracy without independent human review.
Before any accuracy claim or live API run:

1. Have two independent native speakers review every case while blind to the
   model output.
2. Give disagreements to a third adjudicator using the written product policy.
3. Record reviewer IDs, revisions, disagreement reasons, and inter-annotator
   agreement.
4. Freeze the approved files and their SHA-256 digests before testing.
5. Keep this test set hidden from future prompt and threshold tuning.

Real customer data must not be copied into this set while general PII masking is
absent. Use synthetic or approved de-identified material only.

## Offline checks

Run the repository validator without making network or paid API calls:

```bash
./tests/validate-realistic-500.sh
VALIDATE_ONLY=1 ./tests/run-realistic-500-accuracy.sh
```

The validator checks JSONL syntax, exact file/type/language counts, uniqueness,
field schemas, enum values, and core reducer-label coherence. Passing it proves
dataset integrity only; it does not prove moderation accuracy or linguistic
quality.

After human adjudication and explicit API-spend approval, the same wrapper can
run all 500 cases through the existing accuracy harness:

```bash
CONFIRM_LIVE_API=1 \
MODERATION_INTERNAL_RESPONSE_TOKEN='<configured internal token>' \
./tests/run-realistic-500-accuracy.sh
```

The live runner retains the existing opt-in and authentication safeguards.
Expect 500 moderation requests and potentially multiple provider calls per
request; set `MIN_EXACT_ACCURACY` only after agreeing on the release threshold.
