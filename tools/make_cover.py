"""Fiktivt, fotolignende albumcover: fjord om natten med nordlys. Alt er generert i kode."""
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter
from scipy.ndimage import gaussian_filter, map_coordinates

rng = np.random.default_rng(7)
S = 1024
y, x = np.mgrid[0:S, 0:S] / S
img = np.zeros((S, S, 3), np.float32)
HZ = 0.60  # horisont

def lerp(a, b, t): return a + (b - a) * t[..., None]
def col(*c): return np.array(c, np.float32) / 255

def fbm1d(n, octaves=7, seed=0, rough=0.55):
    r = np.random.default_rng(seed); out = np.zeros(n); amp = 1.0
    for o in range(octaves):
        k = 2 ** o + 1
        pts = r.standard_normal(k)
        out += amp * np.interp(np.linspace(0, k - 1, n), np.arange(k), pts)
        amp *= rough
    return out

# --- himmel
t = np.clip(y / HZ, 0, 1)
sky = lerp(col(6, 8, 26), col(28, 18, 62), np.clip(t * 1.6, 0, 1))
sky = lerp(sky, col(96, 34, 88), np.clip((t - 0.55) / 0.3, 0, 1))
sky = lerp(sky, col(214, 104, 92), np.clip((t - 0.82) / 0.14, 0, 1) ** 1.3)
sky = lerp(sky, col(255, 190, 140), np.clip((t - 0.95) / 0.05, 0, 1))
img[:] = sky

# --- stjerner
stars = np.zeros((S, S), np.float32)
n = 2600
sx, sy = rng.integers(0, S, n), (rng.random(n) ** 1.6 * HZ * 0.92 * S).astype(int)
stars[sy, sx] = rng.random(n) ** 6 * 3
stars = gaussian_filter(stars, 0.7) * (1 - np.clip(y / HZ, 0, 1)) ** 1.5
img += stars[..., None] * col(220, 230, 255)

# --- nordlys: buede gardiner med vertikale stråler
X = np.linspace(0, 1, S)
center = 0.27 + 0.07 * np.sin(X * 5.2 + 0.6) + 0.025 * fbm1d(S, 6, 11)
rays = np.clip(0.55 + 0.6 * fbm1d(S, 8, 12, 0.7), 0, None)
dist = y - center[None, :]
curtain = np.exp(-np.clip(dist, 0, None) / 0.035) * np.exp(-np.clip(-dist, 0, None) / 0.16)
curtain *= rays[None, :] * (0.4 + 0.6 * np.exp(-((X - 0.45) / 0.45) ** 2))[None, :]
curtain = gaussian_filter(curtain, (6, 2))
green = col(70, 255, 170); teal = col(60, 200, 255); pink = col(255, 90, 200)
up = np.clip(-dist / 0.18, 0, 1)
aur_col = lerp(np.broadcast_to(green, (S, S, 3)), np.broadcast_to(teal, (S, S, 3)), up * 0.8)
aur_col = lerp(aur_col, np.broadcast_to(pink, (S, S, 3)), np.clip((up - 0.55) / 0.45, 0, 1))
img += aur_col * curtain[..., None] * 0.85
center2 = 0.15 + 0.05 * np.sin(X * 3.1 + 2) + 0.02 * fbm1d(S, 6, 21)
d2 = y - center2[None, :]
c2 = np.exp(-np.clip(d2, 0, None) / 0.03) * np.exp(-np.clip(-d2, 0, None) / 0.1) * np.clip(fbm1d(S, 8, 22, .7) + .3, 0, None)[None, :]
img += gaussian_filter(c2, (5, 2))[..., None] * teal * 0.35

# --- fjell (tre lag, fjernere = disigere)
def ridge(seed, base, height, rough):
    r = fbm1d(S, 9, seed, rough); r = (r - r.min()) / (np.ptp(r) + 1e-6)
    return base - r * height
layers = [(37, HZ - 0.005, 0.22, 0.58, col(70, 40, 90), 0.45),
          (32, HZ + 0.004, 0.25, 0.6, col(30, 18, 44), 0.25),
          (33, HZ + 0.012, 0.13, 0.62, col(10, 7, 18), 0.05)]
mount_mask_total = np.zeros((S, S), np.float32)
for seed, base, hgt, rough, c, haze in layers:
    r = ridge(seed, base, hgt, rough)
    if seed == 32:  # dalen i midten der veien går
        r = r + 0.10 * np.exp(-((X - 0.52) / 0.12) ** 2)
    m = (y >= r[None, :]) & (y < HZ + 0.02)
    m = gaussian_filter(m.astype(np.float32), 0.8)
    # svakt lys fra himmelen på fjellsiden
    shade = c + col(255, 150, 120) * 0.06 * (1 - np.clip((y - r[None, :]) / 0.08, 0, 1))[..., None]
    mixed = lerp(shade, img, np.full((S, S), haze, np.float32))
    img = img * (1 - m[..., None]) + mixed * m[..., None]
    mount_mask_total = np.maximum(mount_mask_total, m)

# --- vann med refleks og krusninger
water = y >= HZ + 0.015
src_y = (2 * (HZ + 0.015) - y) * S
ripple = (np.sin(y * 900 + fbm1d(S, 5, 41)[None, :] * 3) * 1.8 + gaussian_filter(rng.standard_normal((S, S)), (0.6, 12)) * 6)
src_x = x * S + ripple * np.clip((y - HZ) / 0.4, 0, 1) * 1.5
refl = np.stack([map_coordinates(img[..., k], [np.clip(src_y, 0, S - 1), np.clip(src_x, 0, S - 1)], order=1) for k in range(3)], -1)
refl = gaussian_filter(refl, (2.5, 1.2, 0)) * 0.62
depth = np.clip((y - HZ) / 0.4, 0, 1)
refl = lerp(refl, np.broadcast_to(col(4, 4, 12), (S, S, 3)), depth ** 1.3 * 0.8)
img = np.where(water[..., None], refl, img)

# --- strand i forgrunnen (venstre), veien går langs fjorden inn i dalen
yy = np.clip((y - HZ) / (1 - HZ), 0, 1)
edge = 0.475 + yy ** 1.1 * 0.40 + 0.012 * fbm1d(S, 6, 51)[:, None] * yy
land = ((x < edge) & (y > HZ + 0.012)).astype(np.float32)
land = gaussian_filter(land, 1.2)
tex = gaussian_filter(rng.standard_normal((S, S)), 2.0) * 0.012
land_col = col(9, 7, 15) + tex[..., None] + col(60, 30, 50) * (1 - yy)[..., None] * 0.25
img = img * (1 - land[..., None]) + land_col * land[..., None]
# asfalt: svak stripe der veien går
road = np.exp(-((x - (0.49 - yy ** 1.25 * 0.55)) / (0.004 + yy * 0.09)) ** 2) * (y > HZ + 0.015)
img += road[..., None] * col(40, 30, 45) * 0.35

# --- kystvei med lysstriper (lang eksponering)
def trail(points, color, width, glow):
    m = np.zeros((S, S), np.float32)
    pts = np.array(points) * S
    tt = np.linspace(0, 1, 4000)
    # kvadratisk Bézier
    p = ((1 - tt)[:, None] ** 2) * pts[0] + 2 * ((1 - tt) * tt)[:, None] * pts[1] + (tt[:, None] ** 2) * pts[2]
    xi, yi = np.clip(p[:, 0].astype(int), 0, S - 1), np.clip(p[:, 1].astype(int), 0, S - 1)
    w = 0.3 + 0.7 * tt  # tykkere nærmere kamera
    np.maximum.at(m, (yi, xi), w)
    core = gaussian_filter(m, width)
    halo = gaussian_filter(m, glow)
    return (core / (core.max() + 1e-6) * 1.4 + halo / (halo.max() + 1e-6) * 0.5)[..., None] * color
road_y = HZ + 0.07
trails = trail([(0.488, HZ + 0.02), (0.36, HZ + 0.16), (-0.02, 0.93)], col(255, 70, 60), 1.0, 7)
trails += trail([(0.494, HZ + 0.02), (0.40, HZ + 0.17), (0.10, 1.02)], col(255, 225, 180), 1.1, 8)
# veien selv: mørk stripe
img += trails * 0.9
# refleks av lysene i vannet
tr_ref = gaussian_filter(trails, (14, 3, 0)) * 0.25
img += np.roll(tr_ref, int(0.03 * S), axis=0) * (1 - land[..., None]) * 0.6

# --- tåke langs vannet
fog = np.exp(-((y - (HZ + 0.01)) / 0.025) ** 2) * (0.5 + 0.5 * gaussian_filter(rng.random((S, S)), (4, 60)) * 2)
img += fog[..., None] * col(180, 130, 170) * 0.22

# --- noen lys i en bygd ved vannet
for _ in range(14):
    lx = 0.62 + rng.random() * 0.2; ly = HZ + 0.004 + rng.random() * 0.01
    b = np.exp(-(((x - lx) * S) ** 2 + ((y - ly) * S) ** 2) / 3)
    img += b[..., None] * col(255, 200, 130) * 1.2
    img += np.exp(-(((x - lx) * S) ** 2 / 2 + ((y - (2 * HZ + .03 - ly)) * S) ** 2 / 60))[..., None] * col(255, 190, 120) * 0.25

# --- linse: bloom, vignett, kromatisk aberrasjon, korn
bright = np.clip(img - 0.65, 0, None)
img += gaussian_filter(bright, (18, 18, 0)) * 0.9 + gaussian_filter(bright, (60, 60, 0)) * 0.6
r2 = (x - 0.5) ** 2 + (y - 0.5) ** 2
img *= (1 - 0.75 * r2 ** 1.15)[..., None]
shift = (r2 * 6).astype(np.float32)
img[..., 0] = map_coordinates(img[..., 0], [y * S, np.clip(x * S + shift * (x - .5) * 2, 0, S - 1)], order=1)
img[..., 2] = map_coordinates(img[..., 2], [y * S, np.clip(x * S - shift * (x - .5) * 2, 0, S - 1)], order=1)
img = 1 - np.exp(-img * 1.35)  # filmisk tonekurve
img = np.clip(img, 0, 1) ** (1 / 1.05)
grain = gaussian_filter(rng.standard_normal((S, S)), 0.6)[..., None] * 0.028
img = np.clip(img + grain, 0, 1)

base = Image.fromarray((img * 255).astype(np.uint8))
base.save('/home/claude/cover/cover_plain.jpg', quality=90)

# --- typografi (albumcover)
im = base.copy(); dr = ImageDraw.Draw(im)
f_small = ImageFont.truetype('/usr/share/fonts/opentype/inter/Inter-Medium.otf', 30)
f_big = ImageFont.truetype('/usr/share/fonts/opentype/inter/Inter-Thin.otf', 112)
dr.text((70, 74), 'N O R D L Y S   E K S P R E S S', font=f_small, fill=(255, 255, 255, 230))
dr.text((62, 116), 'Nattkjøring', font=f_big, fill=(255, 245, 240))
dr.rectangle((72, 262, 152, 266), fill=(255, 179, 128))
im.save('/home/claude/cover/cover_title.jpg', quality=90)
for name, src in [('cover_title', im), ('cover_plain', base)]:
    src.resize((520, 520), Image.LANCZOS).save(f'/home/claude/cover/{name}_520.jpg', quality=80)
print('ok')
