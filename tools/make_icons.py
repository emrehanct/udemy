# Uygulama simgesi ve TV afişini üretir (GitHub Actions derlemesinde çalışır).
from PIL import Image, ImageDraw, ImageFont
import os

def font(sz):
    for p in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
              "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"]:
        if os.path.exists(p):
            return ImageFont.truetype(p, sz)
    return ImageFont.load_default()

def play(d, cx, cy, r, col):
    d.polygon([(cx - r * 0.45, cy - r * 0.6), (cx - r * 0.45, cy + r * 0.6), (cx + r * 0.6, cy)], fill=col)

res = "app/src/main/res"
for sub in ["drawable", "mipmap-xhdpi", "mipmap-xxhdpi"]:
    os.makedirs(os.path.join(res, sub), exist_ok=True)

S = 4
W, H = 320 * S, 180 * S
im = Image.new("RGB", (W, H))
d = ImageDraw.Draw(im)
for y in range(H):
    t = y / H
    d.line([(0, y), (W, y)], fill=(int(40 + 60 * t), int(20 + 10 * t), int(70 + 60 * t)))
d.ellipse([28 * S, 50 * S, 108 * S, 130 * S], fill=(164, 53, 240))
play(d, 68 * S, 90 * S, 30 * S, (255, 255, 255))
d.text((122 * S, 62 * S), "Ders TV", font=font(40 * S), fill="white")
d.text((124 * S, 110 * S), "Udemy için", font=font(17 * S), fill=(220, 205, 240))
im.resize((320, 180), Image.LANCZOS).save(os.path.join(res, "drawable", "banner.png"))

for name, sz in [("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144)]:
    S2 = sz * 4
    ic = Image.new("RGBA", (S2, S2), (0, 0, 0, 0))
    d = ImageDraw.Draw(ic)
    d.rounded_rectangle([0, 0, S2 - 1, S2 - 1], radius=S2 // 5, fill=(164, 53, 240))
    play(d, S2 / 2 + S2 * 0.04, S2 / 2, S2 * 0.42, (255, 255, 255))
    ic.resize((sz, sz), Image.LANCZOS).save(os.path.join(res, name, "ic_launcher.png"))
print("simgeler hazir")
