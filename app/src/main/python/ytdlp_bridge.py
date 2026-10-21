import json
import yt_dlp
import os

def get_formats(url):
    try:
        with yt_dlp.YoutubeDL({"quiet": True, "skip_download": True}) as ydl:
            info = ydl.extract_info(url, download=False)
            formats = []
            for f in info.get("formats", []):
                if not f.get("url"): continue
                formats.append({
                    "format_id": f.get("format_id", ""),
                    "ext": f.get("ext", ""),
                    "resolution": f.get("resolution", "") or f.get("format_note", ""),
                    "height": f.get("height", 0),
                    "filesize": f.get("filesize") or f.get("filesize_approx", 0),
                    "vcodec": f.get("vcodec", "none"),
                    "acodec": f.get("acodec", "none"),
                })
            return json.dumps({"ok": True, "formats": formats})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})

def download_video(url, format_id, output_dir, ffmpeg_path, callback):
    def progress_hook(d):
        if d['status'] == 'downloading':
            try:
                percent = d.get('_percent_str', '0%').replace('%', '').strip()
                callback.onProgress(float(percent) if percent != 'N/A' else 0.0)
            except: pass
        elif d['status'] == 'finished':
            callback.onProgress(100.0)

    final_format = format_id
    if format_id != "best" and "+" not in format_id:
        final_format = f"{format_id}+bestaudio/best"

    ydl_opts = {
        'format': final_format,
        'outtmpl': os.path.join(output_dir, '%(title)s.%(ext)s'),
        'progress_hooks': [progress_hook],
        'quiet': True,
        'merge_output_format': 'mp4',
        'ffmpeg_location': ffmpeg_path,
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
        return json.dumps({"ok": True})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})
