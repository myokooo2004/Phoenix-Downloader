"""yt-dlp bridge for Phoenix Downloader (Chaquopy).

Exposes simple functions for Kotlin to call:
- get_video_info(url) -> JSON string with title, thumbnail, duration
- get_formats(url) -> JSON string with available formats
- get_stream_url(url, format_id) -> direct stream URL
"""
import json
import yt_dlp


def _base_opts():
    return {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
    }


def get_video_info(url):
    """Return basic video info as JSON."""
    try:
        with yt_dlp.YoutubeDL(_base_opts()) as ydl:
            info = ydl.extract_info(url, download=False)
            return json.dumps({
                "ok": True,
                "title": info.get("title", ""),
                "thumbnail": info.get("thumbnail", ""),
                "duration": info.get("duration", 0),
                "uploader": info.get("uploader", ""),
                "view_count": info.get("view_count", 0),
            })
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})


def get_formats(url):
    """Return available formats as JSON list."""
    try:
        opts = _base_opts()
        opts["listformats"] = False
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
            formats = []
            for f in info.get("formats", []):
                # Only include formats with a URL
                if not f.get("url"):
                    continue
                formats.append({
                    "format_id": f.get("format_id", ""),
                    "ext": f.get("ext", ""),
                    "resolution": f.get("resolution", "") or f.get("format_note", ""),
                    "height": f.get("height", 0),
                    "fps": f.get("fps", 0),
                    "filesize": f.get("filesize") or f.get("filesize_approx", 0),
                    "acodec": f.get("acodec", "none"),
                    "vcodec": f.get("vcodec", "none"),
                    "abr": f.get("abr", 0),
                })
            return json.dumps({"ok": True, "formats": formats})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})


def get_stream_url(url, format_id="best"):
    """Return direct stream URL for a format."""
    try:
        opts = _base_opts()
        opts["format"] = format_id
        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(url, download=False)
            return json.dumps({
                "ok": True,
                "url": info.get("url", ""),
                "ext": info.get("ext", ""),
            })
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})


def get_version():
    """Return yt-dlp version."""
    try:
        return json.dumps({"ok": True, "version": yt_dlp.version.__version__})
    except Exception as e:
        return json.dumps({"ok": False, "error": str(e)})
