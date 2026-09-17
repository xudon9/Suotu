#!/usr/bin/env python3
"""
Check the Gaussian blur kernel and the separable two-pass implementation.

Worth verifying rather than eyeballing:

 - The kernel must be normalised. If the weights do not sum to 1 the image gets
   brighter or darker as it blurs, which looks like a colour bug rather than a blur bug.
 - Two 1-D passes must equal one 2-D pass. That equivalence is the whole reason for the
   separable form, and it is easy to break by reusing the wrong buffer.
 - Edge pixels must be clamped, not treated as transparent, or the region acquires a
   dark halo along its border.
"""

import math


def gaussian_kernel(radius):
    """Mirror of GaussianBlur.gaussianKernel."""
    sigma = max(radius / 2.0, 0.5)
    size = radius * 2 + 1
    denom = 2.0 * sigma * sigma
    k = [math.exp(-((i - radius) ** 2) / denom) for i in range(size)]
    total = sum(k)
    return [v / total for v in k]


def blur_1d(values, kernel):
    """One separable pass with clamped edges."""
    radius = len(kernel) // 2
    n = len(values)
    out = []
    for i in range(n):
        acc = 0.0
        for kx, weight in enumerate(kernel):
            s = min(max(i + kx - radius, 0), n - 1)
            acc += weight * values[s]
        out.append(acc)
    return out


def blur_2d_separable(grid, kernel):
    """Horizontal pass then vertical pass."""
    rows = [blur_1d(row, kernel) for row in grid]
    cols = list(zip(*rows))
    blurred_cols = [blur_1d(list(c), kernel) for c in cols]
    return [list(r) for r in zip(*blurred_cols)]


def blur_2d_direct(grid, kernel):
    """Full 2-D convolution with the outer product of the 1-D kernel."""
    radius = len(kernel) // 2
    h, w = len(grid), len(grid[0])
    out = [[0.0] * w for _ in range(h)]
    for y in range(h):
        for x in range(w):
            acc = 0.0
            for ky, wy in enumerate(kernel):
                for kx, wx in enumerate(kernel):
                    sy = min(max(y + ky - radius, 0), h - 1)
                    sx = min(max(x + kx - radius, 0), w - 1)
                    acc += wy * wx * grid[sy][sx]
            out[y][x] = acc
    return out


def main():
    failures = 0

    print("=== kernel is normalised (no brightness shift) ===")
    print(f"{'radius':>7}{'size':>6}{'sum':>12}{'peak':>9}   ok")
    print("-" * 42)
    for r in (1, 2, 4, 8, 16, 32, 44):
        k = gaussian_kernel(r)
        total = sum(k)
        ok = abs(total - 1.0) < 1e-6
        if not ok:
            failures += 1
        print(f"{r:>7}{len(k):>6}{total:>12.8f}{max(k):>9.4f}   "
              f"{'ok' if ok else 'FAIL'}")

    print("\n=== kernel is symmetric and peaks at the centre ===")
    for r in (3, 10, 25):
        k = gaussian_kernel(r)
        sym = all(abs(k[i] - k[-1 - i]) < 1e-12 for i in range(len(k) // 2))
        peaked = k[r] == max(k)
        ok = sym and peaked
        if not ok:
            failures += 1
        print(f"  radius {r:>3}: symmetric={sym} centre-peak={peaked}   "
              f"{'ok' if ok else 'FAIL'}")

    print("\n=== two 1-D passes == one 2-D convolution ===")
    # An off-centre impulse plus a step edge, which exercises both the interior and
    # the clamped border.
    grid = [[0.0] * 9 for _ in range(9)]
    grid[3][2] = 255.0
    for y in range(9):
        for x in range(6, 9):
            grid[y][x] = 128.0

    for r in (1, 2, 3):
        k = gaussian_kernel(r)
        a = blur_2d_separable(grid, k)
        b = blur_2d_direct(grid, k)
        worst = max(
            abs(a[y][x] - b[y][x]) for y in range(9) for x in range(9)
        )
        ok = worst < 1e-9
        if not ok:
            failures += 1
        print(f"  radius {r}: max difference {worst:.2e}   {'ok' if ok else 'FAIL'}")

    print("\n=== energy is preserved (a flat field stays flat) ===")
    flat = [[200.0] * 7 for _ in range(7)]
    k = gaussian_kernel(3)
    out = blur_2d_separable(flat, k)
    worst = max(abs(out[y][x] - 200.0) for y in range(7) for x in range(7))
    ok = worst < 1e-9
    if not ok:
        failures += 1
    print(f"  flat 200 -> max deviation {worst:.2e}   {'ok' if ok else 'FAIL'}")

    print("\n=== clamped edges: no dark halo at the border ===")
    # A uniform field must stay uniform right up to the edge. Treating off-image
    # samples as 0 would pull the border down.
    k = gaussian_kernel(4)
    row = [100.0] * 20
    out = blur_1d(row, k)
    edge_ok = abs(out[0] - 100.0) < 1e-9 and abs(out[-1] - 100.0) < 1e-9
    if not edge_ok:
        failures += 1
    print(f"  border values {out[0]:.4f} / {out[-1]:.4f} (want 100)   "
          f"{'ok' if edge_ok else 'FAIL'}")

    # Show what the alternative would have done, to justify the clamp.
    def blur_1d_zeropad(values, kernel):
        radius = len(kernel) // 2
        n = len(values)
        return [
            sum(
                w * (values[i + kx - radius]
                     if 0 <= i + kx - radius < n else 0.0)
                for kx, w in enumerate(kernel)
            )
            for i in range(n)
        ]

    zp = blur_1d_zeropad(row, k)
    print(f"  with zero padding the border would be {zp[0]:.1f} "
          f"({100 - zp[0]:.0f} too dark)")

    print("\n=== radius mapping covers a useful range ===")
    MIN, MAX = 0.003, 0.020
    print(f"{'slider':>8}{'radius@1260px':>15}")
    print("-" * 24)
    radii = []
    for i in range(6):
        th = MIN + (MAX - MIN) * i / 5
        t = (th - MIN) / (MAX - MIN)
        radius = 1260 * (0.003 + t * 0.032)
        radii.append(round(radius))
        print(f"{i}/5{'':>5}{radius:>13.1f}px")
    if len(set(radii)) != len(radii):
        print("FAIL: slider positions collapse to the same radius")
        failures += 1
    else:
        print("all six positions give a distinct radius")

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Gaussian kernel, separability and edge handling are correct.")


if __name__ == "__main__":
    main()
