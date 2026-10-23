import sys, math
from PIL import Image, ImageDraw, ImageFilter, ImageFont
out = sys.argv[1]
S = 4  # supersample

def aurora(w, h):
    base = Image.new("RGB", (w, h), (6, 7, 16))
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    for cx, cy, r, col in [(0.05, 0.0, 0.8, (40, 90, 255)), (1.0, 1.0, 0.8, (150, 60, 255)), (0.75, 0.15, 0.45, (20, 200, 180))]:
        layer = Image.new("L", (w, h), 0)
        d = ImageDraw.Draw(layer)
        R = r * w
        d.ellipse([cx * w - R, cy * h - R, cx * w + R, cy * h + R], fill=255)
        layer = layer.filter(ImageFilter.GaussianBlur(R * 0.45))
        tint = Image.new("RGB", (w, h), col)
        glow = Image.composite(Image.blend(glow, tint, 0.85), glow, layer.point(lambda v: int(v * 0.75)))
    return Image.blend(base, glow, 0.92)

def house(d, cx, cy, s, fill):
    # rounded-ish house: roof triangle + body with door cut
    w = s; h = s
    roof = [(cx - w * 0.5, cy - h * 0.02), (cx, cy - h * 0.46), (cx + w * 0.5, cy - h * 0.02)]
    d.polygon(roof, fill=fill)
    d.rounded_rectangle([cx - w * 0.36, cy - h * 0.1, cx + w * 0.36, cy + h * 0.42], radius=w * 0.06, fill=fill)
    # door
    d.rounded_rectangle([cx - w * 0.09, cy + h * 0.14, cx + w * 0.09, cy + h * 0.42], radius=w * 0.04, fill=(40, 60, 140))

def glass_badge(img, box, radius):
    x0, y0, x1, y1 = box
    region = img.crop(box).filter(ImageFilter.GaussianBlur(6 * S))
    overlay = Image.new("RGBA", region.size, (255, 255, 255, 46))
    region = Image.alpha_composite(region.convert("RGBA"), overlay)
    mask = Image.new("L", region.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, region.size[0] - 1, region.size[1] - 1], radius=radius, fill=255)
    img.paste(region.convert("RGB"), (x0, y0), mask)
    d = ImageDraw.Draw(img, "RGBA")
    d.rounded_rectangle(box, radius=radius, outline=(255, 255, 255, 110), width=int(1.5 * S))
    # specular highlight on top half
    hl = Image.new("L", region.size, 0)
    hd = ImageDraw.Draw(hl)
    for i in range(region.size[1] // 2):
        hd.line([(0, i), (region.size[0], i)], fill=int(40 * (1 - i / (region.size[1] / 2))))
    hl = Image.composite(hl, Image.new("L", region.size, 0), mask)
    img.paste(Image.new("RGB", region.size, (255, 255, 255)), (x0, y0), hl)

font = lambda px, weight: ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", px)

# ---- banner 320x180 (xhdpi TV banner)
W, H = 320 * S, 180 * S
img = aurora(W, H)
bs = 76 * S
bx, by = 34 * S, (H - bs) // 2
glass_badge(img, (bx, by, bx + bs, by + bs), 20 * S)
house(ImageDraw.Draw(img), bx + bs / 2, by + bs / 2 + 4 * S, 40 * S, (245, 245, 247))
d = ImageDraw.Draw(img)
f1 = ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", 40 * S)
try:
    f1.set_variation_by_name("Semibold")
except Exception:
    pass
tx = bx + bs + 20 * S
d.text((tx, H / 2 - 47 * S), "Clean", font=f1, fill=(245, 245, 247))
d.text((tx, H / 2 + 1 * S), "Home", font=f1, fill=(245, 245, 247, 255))
img.resize((320, 180), Image.LANCZOS).save(f"{out}/banner.png", optimize=True)

# ---- square icon 192x192
W = H = 192 * S
img = aurora(W, H)
house(ImageDraw.Draw(img), W / 2, H / 2 + 10 * S, 92 * S, (245, 245, 247))
mask = Image.new("L", (W, H), 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, W - 1, H - 1], radius=44 * S, fill=255)
ico = Image.new("RGBA", (W, H), (0, 0, 0, 0))
ico.paste(img, (0, 0), mask)
ico.resize((192, 192), Image.LANCZOS).save(f"{out}/icon.png", optimize=True)
print("ok")
