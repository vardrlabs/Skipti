"""SKIPTI identity masters. Deterministic: same file, every run.

Palette read from vardrlabs.com's own :root, not matched by eye:
  ground #0b0f13 · cyan #5ad0f2 · steel #9db0bf

THE DISTRIBUTION IS THE DESIGN. An earlier version scattered points with
uniform random sampling and it looked like static: random clumps and voids,
blotchy at every size. Points here sit on a VOGEL (sunflower) lattice
(successive points at the golden angle, radius as sqrt(i)), which covers a
disc with no clumping and no gaps, because that is what phyllotaxis is for.
The sparse half keeps every third lattice point, and the spiral arms that
emerges from are a real property of the golden angle, not an artifact: it is
the same family of structure as the rotor cloud on the studio's site.

Do not swap the lattice for random. That is the mistake this file exists to
avoid repeating.
"""
import math

BG, CYAN, STEEL = "#0b0f13", "#5ad0f2", "#9db0bf"
BASE_HI, BASE_LO = "#3fa0bb", "#202a33"
PHI = math.pi * (3 - math.sqrt(5))     # the golden angle
M = -0.55                              # the cut's slope
N_POINTS = 1500                        # constant: dot SIZE scales, not count
KEEP = 3                               # every Nth lattice point below the cut

def vogel(n, cx, cy, r):
    for i in range(n):
        rad = r * math.sqrt((i + 0.5) / n)
        a = i * PHI
        yield i, cx + rad*math.cos(a), cy + rad*math.sin(a)

def mark(cx, cy, r, uid, dense=CYAN, sparse=STEEL, hi=BASE_HI, lo=BASE_LO, cut=BG):
    c = cy - M*cx
    dot = r / 43.0
    above = lambda x, y: y < M*x + c
    P = [f'<clipPath id="cl{uid}"><circle cx="{cx}" cy="{cy}" r="{r}"/></clipPath>',
         f'<g clip-path="url(#cl{uid})">',
         f'<rect x="{cx-r}" y="{cy-r}" width="{2*r}" height="{2*r}" fill="{lo}"/>',
         f'<path d="M {cx-2*r} {M*(cx-2*r)+c:.2f} L {cx+2*r} {M*(cx+2*r)+c:.2f} '
         f'L {cx+2*r} {cy-2*r} L {cx-2*r} {cy-2*r} Z" fill="{hi}"/>']
    for i, x, y in vogel(N_POINTS, cx, cy, r):
        if above(x, y):
            P.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="{dot:.2f}" fill="{dense}" opacity="0.95"/>')
        elif i % KEEP == 0:
            P.append(f'<circle cx="{x:.2f}" cy="{y:.2f}" r="{dot*0.82:.2f}" fill="{sparse}" opacity="0.85"/>')
    P.append(f'<path d="M {cx-2*r} {M*(cx-2*r)+c:.2f} L {cx+2*r} {M*(cx+2*r)+c:.2f}" '
             f'stroke="{cut}" stroke-width="{r*0.092:.2f}" fill="none"/>')
    P.append('</g>')
    return "".join(P)

def doc(w, body, bg=BG):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{w}" '
            f'viewBox="0 0 {w} {w}">'
            + (f'<rect width="{w}" height="{w}" fill="{bg}"/>' if bg else "")
            + body + '</svg>\n')

open("brand/skipti-icon-ios-1024.svg","w").write(doc(1024, mark(512, 512, 318, 1)))
open("brand/skipti-adaptive-foreground.svg","w").write(doc(108, mark(54, 54, 30, 2), bg=None))
open("brand/skipti-adaptive-background.svg","w").write(doc(108, "", bg=BG))
open("brand/skipti-mark.svg","w").write(doc(120, mark(60, 60, 37, 3), bg=None))
open("brand/skipti-mark-mono.svg","w").write(
    doc(120, mark(60, 60, 37, 4, dense="#e9eef3", sparse="#e9eef3",
                 hi="#6e7e8b", lo="#2a333b", cut="#0b0f13"), bg=None))
print("wrote 5 masters on the Vogel lattice")
