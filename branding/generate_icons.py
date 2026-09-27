"""Render the original Latitude vector mark to the Android legacy PNG resources."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
SCALE = 8
W = 108 * SCALE
mark = Image.new("RGBA", (W, W), (0, 0, 0, 0))
draw = ImageDraw.Draw(mark)

def xy(x, y):
    return (round(x * SCALE), round(y * SCALE))

def bezier(points, color, width=3):
    p0, p1, p2, p3 = points
    curve = []
    for i in range(101):
        t = i / 100
        u = 1 - t
        curve.append(xy(u**3*p0[0]+3*u*u*t*p1[0]+3*u*t*t*p2[0]+t**3*p3[0],
                        u**3*p0[1]+3*u*u*t*p1[1]+3*u*t*t*p2[1]+t**3*p3[1]))
    draw.line(curve, fill=color, width=round(width * SCALE), joint="curve")

mint = "#A4F1E7"
cyan = "#63CFE0"
white = "#E9FFFB"
draw.ellipse((*xy(22, 22), *xy(86, 86)), outline=mint, width=round(3.5*SCALE))
bezier([(54,22),(39,35),(39,73),(54,86)], cyan)
bezier([(54,22),(69,35),(69,73),(54,86)], cyan)
draw.line([xy(23,54),xy(85,54)], fill=white, width=3*SCALE)
bezier([(27,40),(44,46),(64,46),(81,40)], white)
bezier([(27,68),(44,62),(64,62),(81,68)], white)
draw.ellipse((*xy(50,50), *xy(58,58)), fill=white)
mark.resize((512,512), Image.Resampling.LANCZOS).save(ROOT / "drawable-nodpi/ic_launcher_foreground.png")

for density, size in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)):
    width=size*SCALE
    background=Image.new("RGBA",(width,width))
    bg=ImageDraw.Draw(background)
    for y in range(width):
        t=y/(width-1)
        bg.line((0,y,width,y), fill=(round(16*(1-t)+7*t),round(40*(1-t)+21*t),round(60*(1-t)+31*t),255))
    background.alpha_composite(mark.resize((width,width),Image.Resampling.LANCZOS))
    out=background.resize((size,size),Image.Resampling.LANCZOS)
    for name in ("ic_launcher.png","ic_launcher_round.png"):
        out.save(ROOT / f"mipmap-{density}" / name)
