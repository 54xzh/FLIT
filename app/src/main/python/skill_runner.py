import contextlib
import io
import inspect
import json
import os
import pathlib
import runpy
import sys
import traceback


def _truncate(text: str, max_chars: int):
    if max_chars <= 0:
        return "", bool(text)
    if text is None:
        return "", False
    if len(text) <= max_chars:
        return text, False
    return text[:max_chars] + "\n... (truncated)", True


def _patch_pathlib_write_text_newline():
    """
    Backport `Path.write_text(..., newline=...)` for older Python runtimes where
    `newline` is not supported (e.g., Python < 3.10).

    Returns a list of (cls, original_write_text) for restoration.
    """
    patched = []

    def needs_newline_backport(fn):
        try:
            sig = inspect.signature(fn)
            return "newline" not in sig.parameters
        except Exception:
            return False

    def make_wrapper(orig_fn):
        def write_text(self, data, encoding=None, errors=None, newline=None):
            if newline is None:
                return orig_fn(self, data, encoding=encoding, errors=errors)
            with self.open(mode="w", encoding=encoding, errors=errors, newline=newline) as f:
                return f.write(data)

        return write_text

    for cls_name in ("Path", "PosixPath", "WindowsPath"):
        cls = getattr(pathlib, cls_name, None)
        if cls is None:
            continue
        orig = getattr(cls, "write_text", None)
        if orig is None:
            continue
        if not needs_newline_backport(orig):
            continue
        setattr(cls, "write_text", make_wrapper(orig))
        patched.append((cls, orig))

    return patched


def _coerce_sys_paths(value):
    if value is None:
        return []
    if isinstance(value, (str, bytes, bytearray)):
        return [value]

    try:
        return list(value)
    except TypeError:
        pass

    size = getattr(value, "size", None)
    get = getattr(value, "get", None)
    if callable(size) and callable(get):
        try:
            return [get(i) for i in range(size())]
        except Exception:
            return []

    try:
        n = len(value)
        return [value[i] for i in range(n)]
    except Exception:
        return []


def run_script(
    script_path: str,
    input_json,
    work_dir: str,
    max_stdout_chars: int = 20000,
    max_stderr_chars: int = 20000,
    extra_sys_paths=None,
) -> str:
    """
    Executes a Python script file and calls its `run(input: dict)` function.
    Returns a JSON string containing {ok, result, stdout, stderr, error}.
    """
    ok = False
    result = None
    error = None
    stdout_text = ""
    stderr_text = ""
    stdout_truncated = False
    stderr_truncated = False

    try:
        input_obj = json.loads(input_json) if input_json else {}
        if input_obj is None:
            input_obj = {}
        if not isinstance(input_obj, dict):
            input_obj = {"_value": input_obj}
    except Exception:
        input_obj = {}

    argv = None
    raw_argv = input_obj.get("argv") or input_obj.get("_argv")
    if isinstance(raw_argv, list) and all(isinstance(x, str) for x in raw_argv):
        argv = raw_argv

    out_buf = io.StringIO()
    err_buf = io.StringIO()

    prev_cwd = os.getcwd()
    prev_argv = list(sys.argv)
    prev_sys_path = list(sys.path)
    try:
        os.makedirs(work_dir, exist_ok=True)
        os.chdir(work_dir)

        with contextlib.redirect_stdout(out_buf), contextlib.redirect_stderr(err_buf):
            patched_pathlib = []
            try:
                sys.argv = [script_path]

                script_dir = os.path.dirname(os.path.abspath(script_path))
                skill_root_dir = os.path.dirname(script_dir) if script_dir else ""

                prefix_paths = []
                if skill_root_dir:
                    prefix_paths.append(skill_root_dir)
                if script_dir:
                    prefix_paths.append(script_dir)

                for p in _coerce_sys_paths(extra_sys_paths):
                    if p is None:
                        continue
                    p = str(p)
                    if p and os.path.isdir(p):
                        prefix_paths.append(p)

                if prefix_paths:
                    seen = set()
                    ordered_prefix = []
                    for p in prefix_paths:
                        if p not in seen:
                            seen.add(p)
                            ordered_prefix.append(p)

                    sys.path[:] = ordered_prefix + [p for p in prev_sys_path if p not in seen]

                patched_pathlib = _patch_pathlib_write_text_newline()

                scope = runpy.run_path(script_path, run_name="__skill__")
                fn = scope.get("run")
                if callable(fn):
                    result = fn(input_obj)
                    ok = True
                else:
                    if argv is None:
                        raise RuntimeError(
                            "脚本未提供 run(input: dict) 函数。若该脚本为命令行脚本，请传入 argv（或 input.argv）参数列表，例如 ['--help'] 查看用法。"
                        )
                    sys.argv = [script_path] + argv
                    runpy.run_path(script_path, run_name="__main__")
                    result = None
                    ok = True
            finally:
                for cls, orig in reversed(patched_pathlib):
                    try:
                        setattr(cls, "write_text", orig)
                    except Exception:
                        pass
                sys.argv = prev_argv
                sys.path[:] = prev_sys_path
    except BaseException as e:
        if isinstance(e, SystemExit) and e.code in (0, None):
            ok = True
            error = None
        else:
            ok = False
            if isinstance(e, SystemExit) and e.code == 2:
                error = "SystemExit: 2（可能是 argparse 参数错误：请检查 argv（或 input.argv），或改用 run(input) 入口）"
            else:
                error = str(e) or e.__class__.__name__
            err_buf.write("\n")
            err_buf.write(traceback.format_exc())
    finally:
        try:
            os.chdir(prev_cwd)
        except Exception:
            pass

    stdout_text, stdout_truncated = _truncate(out_buf.getvalue(), int(max_stdout_chars))
    stderr_text, stderr_truncated = _truncate(err_buf.getvalue(), int(max_stderr_chars))

    if ok:
        try:
            json.dumps(result)
        except Exception:
            result = {"_repr": repr(result)}

    payload = {
        "ok": ok,
        "result": result,
        "stdout": stdout_text,
        "stderr": stderr_text,
        "error": error,
        "truncated": {"stdout": stdout_truncated, "stderr": stderr_truncated},
    }
    return json.dumps(payload, ensure_ascii=False)
