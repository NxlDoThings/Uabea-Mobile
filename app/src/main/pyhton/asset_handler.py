"""
asset_handler.py - UnityPy backend for UABEA Mobile.

Public API (called from Kotlin through Chaquopy):

    environment_info()                                           -> JSON object
    list_textures(file_path)                                     -> JSON list
    export_texture(file_path, path_id, out_png_path)             -> JSON object
    replace_texture(file_path, path_id, new_png_path,
                    out_assets_path, keep_size=True)             -> JSON object
    release_memory()                                             -> None

Failures are raised as RuntimeError with a readable message; Kotlin shows it.
"""
import importlib
import inspect
import json
import os
import sys
import types

# ---------------------------------------------------------------------------
# Optional native decoders
#
# texture2ddecoder, etcpak and astc-encoder-py (astc_encoder) are C extensions.
# They are not on Chaquopy's package repository and PyPI has no Android wheels
# for them, so they are only present if you drop self-built Android wheels into
# app/wheels (see the README there).  When one cannot be imported we register a
# placeholder module so that `import UnityPy` still succeeds; any attempt to
# actually use the missing decoder then raises a clear RuntimeError, which the
# app reports to the user (formats Pillow can handle keep working).
# ---------------------------------------------------------------------------
_NATIVE_MODULES = ("texture2ddecoder", "etcpak", "astc_encoder")
_NATIVE_PROBLEMS = {}  # module name -> why it could not be imported


class _Unavailable(object):
    """Stands in for any attribute of a native module that failed to import."""

    def __init__(self, qualified_name):
        self._qualified_name = qualified_name

    def __getattr__(self, item):
        if item.startswith("_"):
            raise AttributeError(item)
        return _Unavailable(self._qualified_name + "." + item)

    def __call__(self, *args, **kwargs):
        raise RuntimeError(
            "'%s' is not available in this build (the native module could not be "
            "loaded on Android). Textures in this format cannot be decoded or "
            "re-encoded; see app/wheels/README.txt." % self._qualified_name
        )

    def __repr__(self):
        return "<unavailable %s>" % self._qualified_name


class _StubModule(types.ModuleType):
    def __getattr__(self, item):
        if item.startswith("_"):
            raise AttributeError(item)
        return _Unavailable(self.__name__ + "." + item)


def _probe_native_modules():
    for name in _NATIVE_MODULES:
        try:
            importlib.import_module(name)
        except Exception as exc:  # ImportError, OSError for a bad .so, ...
            _NATIVE_PROBLEMS[name] = "%s: %s" % (type(exc).__name__, exc)
            sys.modules[name] = _StubModule(name)


_probe_native_modules()

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
_UNITYPY = None
_ENV_CACHE = {"key": None, "env": None}


def _unitypy():
    global _UNITYPY
    if _UNITYPY is None:
        try:
            import UnityPy  # noqa: WPS433 (import inside function on purpose)
        except Exception as exc:
            raise RuntimeError(
                "UnityPy could not be imported: %s: %s" % (type(exc).__name__, exc)
            )
        _UNITYPY = UnityPy
    return _UNITYPY


def _drop_cache():
    _ENV_CACHE["key"] = None
    _ENV_CACHE["env"] = None


def _load_env(file_path):
    """Load a .assets / bundle file with UnityPy, re-using the last environment."""
    file_path = os.path.abspath(file_path)
    if not os.path.isfile(file_path):
        raise RuntimeError("File not found: %s" % file_path)
    stat = os.stat(file_path)
    key = (file_path, stat.st_mtime_ns, stat.st_size)
    if _ENV_CACHE["key"] == key and _ENV_CACHE["env"] is not None:
        return _ENV_CACHE["env"]
    _drop_cache()  # free the previous file before loading the next one
    env = _unitypy().load(file_path)
    _ENV_CACHE["key"] = key
    _ENV_CACHE["env"] = env
    return env


def _type_name(obj):
    obj_type = getattr(obj, "type", None)
    return getattr(obj_type, "name", str(obj_type))


def _texture_objects(env):
    for obj in env.objects:
        if _type_name(obj) == "Texture2D":
            yield obj


def _find_texture(env, path_id):
    wanted = int(path_id)
    for obj in _texture_objects(env):
        if int(obj.path_id) == wanted:
            return obj
    raise RuntimeError("Texture2D with path_id %d was not found in this file." % wanted)


def _format_name(fmt):
    name = getattr(fmt, "name", None)
    if name:
        return str(name)
    try:
        from UnityPy.enums import TextureFormat

        return TextureFormat(int(fmt)).name
    except Exception:
        return str(fmt)


def _stream_info(tex, file_path):
    """Return (stream_path, stream_missing) for a texture that lives in a .resS."""
    stream = getattr(tex, "m_StreamData", None)
    if stream is None:
        return "", False
    stream_path = str(getattr(stream, "path", "") or "")
    stream_size = int(getattr(stream, "size", 0) or 0)
    if not stream_path or not stream_size or stream_path.startswith("archive:"):
        return stream_path, False
    sibling = os.path.join(os.path.dirname(os.path.abspath(file_path)), os.path.basename(stream_path))
    return stream_path, not os.path.isfile(sibling)


def _require_stream(tex, file_path):
    stream_path, missing = _stream_info(tex, file_path)
    if missing:
        raise RuntimeError(
            "This texture's pixel data is stored in '%s', which is not next to the "
            ".assets file. Tap \"Attach .resS\" and pick that file, then try again."
            % os.path.basename(stream_path)
        )


def _png_safe(img):
    if img.mode in ("1", "L", "LA", "P", "RGB", "RGBA", "I;16"):
        return img
    return img.convert("RGBA")


def _apply_image(tex, img, fmt, mips):
    """Put img into tex. fmt None = legacy `tex.image = img` path."""
    if fmt is None or not hasattr(tex, "set_image"):
        tex.image = img
        return
    params = inspect.signature(tex.set_image).parameters
    kwargs = {}
    if "target_format" in params:
        kwargs["target_format"] = fmt
    if "mipmap_count" in params:
        kwargs["mipmap_count"] = mips
    tex.set_image(img, **kwargs)


def _serialize_env(env):
    """Return the bytes of the loaded file (bundle or serialized/.assets file)."""
    target = getattr(env, "file", None)
    if target is None:
        files = list(getattr(env, "files", {}).values())
        if len(files) != 1:
            raise RuntimeError(
                "Cannot tell which file to write back (%d top-level files loaded)." % len(files)
            )
        target = files[0]
    save = target.save
    try:
        accepts_packer = "packer" in inspect.signature(save).parameters
    except (TypeError, ValueError):
        accepts_packer = False
    if accepts_packer:
        try:
            data = save(packer="original")
        except (TypeError, ValueError):
            data = save()
    else:
        data = save()
    return bytes(data)


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------
def environment_info():
    """Diagnostics for the UI banner. Never raises."""
    info = {
        "python": sys.version.split()[0],
        "unitypy": None,
        "pillow": None,
        "native_missing": dict(_NATIVE_PROBLEMS),
        "error": None,
    }
    try:
        unitypy = _unitypy()
        info["unitypy"] = str(getattr(unitypy, "__version__", "unknown"))
        import PIL

        info["pillow"] = str(PIL.__version__)
    except Exception as exc:
        info["error"] = str(exc)
    return json.dumps(info)


def release_memory():
    """Forget the cached UnityPy environment (called before loading another file)."""
    _drop_cache()


def list_textures(file_path):
    """JSON list of every Texture2D: path_id, name, width, height, m_TextureFormat."""
    env = _load_env(file_path)
    rows = []
    for obj in _texture_objects(env):
        path_id = int(obj.path_id)
        try:
            tex = obj.read()
        except Exception as exc:
            rows.append(
                {
                    "path_id": path_id,
                    "name": "<unreadable texture>",
                    "width": 0,
                    "height": 0,
                    "m_TextureFormat": "UNREADABLE",
                    "stream_path": "",
                    "stream_missing": False,
                    "error": "%s: %s" % (type(exc).__name__, exc),
                }
            )
            continue
        stream_path, stream_missing = _stream_info(tex, file_path)
        rows.append(
            {
                "path_id": path_id,
                "name": str(getattr(tex, "m_Name", "") or ""),
                "width": int(getattr(tex, "m_Width", 0) or 0),
                "height": int(getattr(tex, "m_Height", 0) or 0),
                "m_TextureFormat": _format_name(getattr(tex, "m_TextureFormat", "?")),
                "stream_path": stream_path,
                "stream_missing": stream_missing,
            }
        )
    rows.sort(key=lambda row: (row["name"].lower(), row["path_id"]))
    return json.dumps(rows)


def export_texture(file_path, path_id, out_png_path):
    """Save the selected Texture2D as a PNG (used for the preview and for export)."""
    env = _load_env(file_path)
    obj = _find_texture(env, path_id)
    tex = obj.read()
    _require_stream(tex, file_path)
    img = _png_safe(tex.image)
    out_dir = os.path.dirname(os.path.abspath(out_png_path))
    os.makedirs(out_dir, exist_ok=True)
    img.save(out_png_path, format="PNG")
    return json.dumps(
        {
            "path_id": int(obj.path_id),
            "name": str(getattr(tex, "m_Name", "") or ""),
            "width": int(img.width),
            "height": int(img.height),
            "m_TextureFormat": _format_name(getattr(tex, "m_TextureFormat", "?")),
            "png": out_png_path,
        }
    )


def replace_texture(file_path, path_id, new_png_path, out_assets_path, keep_size=True):
    """
    Replace one Texture2D with the image in new_png_path and write the modified
    file to out_assets_path (never the same path as file_path).

    The replacement is converted to RGBA and, when keep_size is true, resized to
    the original width/height. It is then encoded in the texture's original
    format and mip count. If that fails (for example the encoder is not
    available), it falls back to fewer mips and finally to uncompressed RGBA32,
    which every Unity platform can load.
    """
    from PIL import Image
    from UnityPy.enums import TextureFormat

    if os.path.abspath(out_assets_path) == os.path.abspath(file_path):
        raise RuntimeError("The output file must be different from the input file.")

    env = _load_env(file_path)
    obj = _find_texture(env, path_id)
    tex = obj.read()

    orig_w = int(tex.m_Width)
    orig_h = int(tex.m_Height)
    orig_format = tex.m_TextureFormat
    orig_format_name = _format_name(orig_format)
    orig_mips = int(getattr(tex, "m_MipCount", 1) or 1)

    with Image.open(new_png_path) as source:
        img = source.convert("RGBA")

    if keep_size and img.size != (orig_w, orig_h):
        resampling = getattr(Image, "Resampling", Image)
        img = img.resize((orig_w, orig_h), resampling.LANCZOS)

    if hasattr(tex, "set_image"):
        attempts = [("%s with %d mip(s)" % (orig_format_name, orig_mips), orig_format, orig_mips)]
        if orig_mips != 1:
            attempts.append(("%s without mipmaps" % orig_format_name, orig_format, 1))
        attempts.append(("RGBA32", TextureFormat.RGBA32, 1))
    else:
        attempts = [("image setter", None, None)]

    errors = []
    saved = None
    saved_label = None
    for label, fmt, mips in attempts:
        candidate = obj.read()  # fresh object so a failed attempt leaves no trace
        try:
            _apply_image(candidate, img, fmt, mips)
            candidate.save()
        except Exception as exc:
            errors.append("%s: %s: %s" % (label, type(exc).__name__, exc))
            continue
        saved = candidate
        saved_label = label
        break

    if saved is None:
        raise RuntimeError("Could not encode the replacement image.\n" + "\n".join(errors))

    note = ""
    if errors:
        note = "Could not keep %s (%s). Stored as %s instead." % (
            orig_format_name,
            errors[0],
            saved_label,
        )

    data = _serialize_env(env)
    out_dir = os.path.dirname(os.path.abspath(out_assets_path))
    os.makedirs(out_dir, exist_ok=True)
    tmp_path = out_assets_path + ".tmp"
    with open(tmp_path, "wb") as handle:
        handle.write(data)
    os.replace(tmp_path, out_assets_path)
    _drop_cache()  # the in-memory environment now differs from the input file

    return json.dumps(
        {
            "path_id": int(obj.path_id),
            "name": str(getattr(saved, "m_Name", "") or ""),
            "width": int(saved.m_Width),
            "height": int(saved.m_Height),
            "m_TextureFormat": _format_name(saved.m_TextureFormat),
            "bytes": len(data),
            "note": note,
        }
      )
