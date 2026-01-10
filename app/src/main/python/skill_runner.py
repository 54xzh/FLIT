import contextlib
import io
import json
import os
import runpy
import traceback


def _truncate(text: str, max_chars: int):
    if max_chars <= 0:
        return "", bool(text)
    if text is None:
        return "", False
    if len(text) <= max_chars:
        return text, False
    return text[:max_chars] + "\n... (truncated)", True


def run_script(
    script_path: str,
    input_json,
    work_dir: str,
    max_stdout_chars: int = 20000,
    max_stderr_chars: int = 20000,
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
    except Exception:
        input_obj = {}

    out_buf = io.StringIO()
    err_buf = io.StringIO()

    prev_cwd = os.getcwd()
    try:
        os.makedirs(work_dir, exist_ok=True)
        os.chdir(work_dir)

        with contextlib.redirect_stdout(out_buf), contextlib.redirect_stderr(err_buf):
            scope = runpy.run_path(script_path, run_name="__main__")
            fn = scope.get("run")
            if not callable(fn):
                raise RuntimeError("脚本未提供 run(input: dict) 函数")

            result = fn(input_obj)
            ok = True
    except Exception as e:
        error = str(e)
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
