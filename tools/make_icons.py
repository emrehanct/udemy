# Uygulama simgesi ve TV afişini üretir (GitHub Actions derlemesinde çalışır).
# tools/logo.b64 varsa (kullanıcının kendi logosu) simge ve afiş ondan üretilir.
from PIL import Image, ImageDraw
import base64, io, os

res = "app/src/main/res"
for sub in ["drawable", "drawable-xhdpi", "mipmap-xhdpi", "mipmap-xxhdpi"]:
    os.makedirs(os.path.join(res, sub), exist_ok=True)

logo = Image.open(io.BytesIO(base64.b64decode(open("tools/logo.b64").read()))).convert("RGB")

# TV ana ekran afişi: siyah zemin, logo ortada
for sub, (W, H) in [("drawable", (320, 180)), ("drawable-xhdpi", (640, 360))]:
    bg = Image.new("RGB", (W, H), (0, 0, 0))
    s = int(H * 0.96)
    bg.paste(logo.resize((s, s), Image.LANCZOS), ((W - s) // 2, (H - s) // 2))
    bg.save(os.path.join(res, sub, "banner.png"))

# Uygulama simgesi
for sub, sz in [("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144)]:
    logo.resize((sz, sz), Image.LANCZOS).save(os.path.join(res, sub, "ic_launcher.png"))
print("simgeler hazir")
