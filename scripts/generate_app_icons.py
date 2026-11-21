import os
from PIL import Image, ImageDraw

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192
}

def create_rounded_mask(size, radius):
    # Supersample 4x for silky smooth antialiasing
    scale = 4
    large_size = (size[0] * scale, size[1] * scale)
    large_radius = radius * scale
    mask = Image.new('L', large_size, 0)
    draw = ImageDraw.Draw(mask)
    draw.rounded_rectangle([(0, 0), large_size], radius=large_radius, fill=255)
    return mask.resize(size, Image.Resampling.LANCZOS)

def create_circle_mask(size):
    scale = 4
    large_size = (size[0] * scale, size[1] * scale)
    mask = Image.new('L', large_size, 0)
    draw = ImageDraw.Draw(mask)
    draw.ellipse([(0, 0), large_size], fill=255)
    return mask.resize(size, Image.Resampling.LANCZOS)

def generate_icons(src_path, target_res_dir):
    src_img = Image.open(src_path).convert("RGBA")
    
    for folder, size in DENSITIES.items():
        out_dir = os.path.join(target_res_dir, folder)
        os.makedirs(out_dir, exist_ok=True)
        
        # 1. Square / Squircle Icon
        resized = src_img.resize((size, size), Image.Resampling.LANCZOS)
        squircle_mask = create_rounded_mask((size, size), int(size * 0.225))
        
        squircle_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        squircle_icon.paste(resized, (0, 0), squircle_mask)
        squircle_icon.save(os.path.join(out_dir, "ic_launcher.png"), "PNG")
        
        # 2. Round Icon
        circle_mask = create_circle_mask((size, size))
        round_icon = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        round_icon.paste(resized, (0, 0), circle_mask)
        round_icon.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG")
        
        print(f"Generated {folder}: {size}x{size} -> {out_dir}")

if __name__ == "__main__":
    n1_src = "/Users/jimmy/.gemini/antigravity-cli/brain/ac842a32-828a-4781-9540-c97e07004ded/n1_tools_app_icon_clean_1794897469775.jpg"
    free_src = "/Users/jimmy/.gemini/antigravity-cli/brain/ac842a32-828a-4781-9540-c97e07004ded/free_display_app_icon_1794897517307.jpg"
    
    print("Generating N1 Tools icons...")
    generate_icons(n1_src, "app/src/main/res")
    
    print("Generating Free Display icons...")
    generate_icons(free_src, "freedisplay/src/main/res")
    print("Done!")
