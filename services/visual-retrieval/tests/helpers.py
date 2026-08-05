from __future__ import annotations

import io
import random

import cv2
import numpy as np
from PIL import Image, ImageDraw


def synthetic_image(seed: int, width: int = 720, height: int = 480) -> bytes:
    randomizer = random.Random(seed)
    image = Image.new("RGB", (width, height), (238, 242, 247))
    draw = ImageDraw.Draw(image)
    for index in range(90):
        x = randomizer.randint(10, width - 70)
        y = randomizer.randint(10, height - 70)
        size = randomizer.randint(12, 58)
        color = tuple(randomizer.randint(15, 220) for _ in range(3))
        if index % 3 == 0:
            draw.ellipse((x, y, x + size, y + size), outline=color, width=3)
        elif index % 3 == 1:
            draw.rectangle((x, y, x + size, y + size), outline=color, width=3)
        else:
            draw.line(
                (x, y, x + size, y + randomizer.randint(-10, size)),
                fill=color,
                width=4,
            )
    draw.rounded_rectangle(
        (140, 165, 580, 315), radius=18, fill=(35, 39, 47), outline=(255, 255, 255), width=3
    )
    draw.text((185, 205), "SHARED OFFER TEXT 2026", fill=(255, 255, 255), stroke_width=1)
    draw.text((225, 250), f"template-{seed}", fill=(255, 210, 70), stroke_width=1)
    output = io.BytesIO()
    image.save(output, format="PNG")
    return output.getvalue()


def rotate_image(image_bytes: bytes, angle: float = 6.5) -> bytes:
    source = cv2.imdecode(np.frombuffer(image_bytes, dtype=np.uint8), cv2.IMREAD_COLOR)
    height, width = source.shape[:2]
    transform = cv2.getRotationMatrix2D((width / 2, height / 2), angle, 0.96)
    rotated = cv2.warpAffine(
        source,
        transform,
        (width, height),
        flags=cv2.INTER_LINEAR,
        borderMode=cv2.BORDER_CONSTANT,
        borderValue=(245, 245, 245),
    )
    encoded, buffer = cv2.imencode(".png", rotated)
    assert encoded
    return buffer.tobytes()
