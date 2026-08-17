import io
import unittest

import server


class StructuredOutputTest(unittest.TestCase):
    def test_chunked_body_is_decoded_with_extensions_and_trailers(self):
        encoded = io.BytesIO(
            b"4;test=yes\r\nWiki\r\n5\r\npedia\r\n0\r\nX-Test: one\r\n\r\n"
        )

        self.assertEqual(server.read_chunked_body(encoded, 32), b"Wikipedia")

    def test_chunked_body_enforces_the_bounded_request_size(self):
        with self.assertRaises(server.RequestBodyError) as raised:
            server.read_chunked_body(io.BytesIO(b"5\r\nhello\r\n0\r\n\r\n"), 4)

        self.assertEqual(raised.exception.status, 413)

    def test_classification_uses_safe_schema_values(self):
        payload = {
            "text": {
                "format": {
                    "schema": {
                        "properties": {
                            "safetyDisposition": {
                                "type": "string",
                                "enum": ["allow_none", "block_vulgar"],
                            },
                            "financialRisk": {
                                "type": "string",
                                "enum": ["none", "phishing"],
                            },
                        },
                        "required": ["safetyDisposition", "financialRisk"],
                    }
                }
            }
        }

        self.assertEqual(
            server.structured_output(payload),
            {"safetyDisposition": "allow_none", "financialRisk": "none"},
        )

    def test_unknown_enum_falls_back_to_first_governed_value(self):
        payload = {
            "text": {
                "format": {
                    "schema": {
                        "properties": {
                            "result": {"type": "string", "enum": ["first", "second"]}
                        },
                        "required": ["result"],
                    }
                }
            }
        }

        self.assertEqual(server.structured_output(payload), {"result": "first"})

    def test_adjudication_uses_candidate_from_escaped_prompt_context(self):
        payload = {
            "input": [
                {
                    "content": [
                        {
                            "text": '{"candidateEvidence":{"referenceId":"reference-7"}}'
                        }
                    ]
                }
            ],
            "text": {
                "format": {
                    "schema": {
                        "properties": {
                            "candidateIds": {
                                "type": "array",
                                "items": {"type": "string"},
                                "minItems": 1,
                            }
                        },
                        "required": ["candidateIds"],
                    }
                }
            },
        }

        self.assertEqual(
            server.structured_output(payload), {"candidateIds": ["reference-7"]}
        )


if __name__ == "__main__":
    unittest.main()
