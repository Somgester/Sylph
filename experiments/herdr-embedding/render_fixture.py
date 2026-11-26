"""Render synthetic BGRA frames over stdin/stdout for the JavaFX transport experiment."""

import sys


def render(width, height, x, y, focused, key):
    if not 1 <= width <= 960 or not 1 <= height <= 540:
        raise ValueError("Invalid frame size")

    accent = bytes((80, 220, 80, 255) if focused else (220, 140, 60, 255))
    background = bytes((35 + key % 150, 25, 30, 255))
    cursor = bytes((255, 255, 255, 255))
    left, right = max(0, x - 5), min(width, x + 6)

    for row in range(height):
        if row < 8:
            pixels = accent * width
        elif abs(row - y) <= 5 and left < right:
            pixels = background * left + cursor * (right - left) + background * (width - right)
        else:
            pixels = background * width
        sys.stdout.buffer.write(pixels)
    sys.stdout.buffer.flush()


for line in sys.stdin:
    render(*map(int, line.split()))
