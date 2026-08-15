"""Query the vendored nhentai API v2 OpenAPI spec.

Intended for humans and coding agents that need endpoint details without reading
the whole ~190KB spec. Stdlib only, so it runs with no install step.

    python scripts/apiref.py find favorite
    python scripts/apiref.py show GET /api/v2/galleries
    python scripts/apiref.py schema Gallery
    python scripts/apiref.py list --auth public
    python scripts/apiref.py refresh
"""

import argparse
import json
import re
import sys
import urllib.request
from os import path
from typing import Any, Iterator

# Paths
DOCS_DIR = path.join(path.join(path.dirname(path.abspath(__file__)), ".."), "docs")
SPEC_FILE = path.join(DOCS_DIR, "openapi.json")

# URLS
SPEC_URL = "https://nhentai.net/api/v2/openapi.json"
USER_AGENT = "NClient/apiref (https://github.com/maxwai/NClientV3)"

# Rendering
METHODS = ("get", "post", "put", "patch", "delete")
DEFAULT_DEPTH = 3

AUTH_RE = re.compile(r"\*\*Auth:\*\*\s*(.+)")
RATE_RE = re.compile(r"When `auth=([a-z|]+)`:\s*\n\s*-\s*(\S+)")
RATE_FLAT_RE = re.compile(r"-\s*(\d+/\S+)(?:\s+per\s+(.+?))?\s*$")


def load_spec() -> dict:
    if not path.exists(SPEC_FILE):
        sys.exit(f"Spec not found at {SPEC_FILE}. Run: python scripts/apiref.py refresh")
    with open(SPEC_FILE, "r", encoding="utf-8") as reader:
        return json.load(reader)


def iter_operations(spec: dict) -> Iterator[tuple[str, str, dict]]:
    """Yield (method, path, operation) for every real operation, sorted by path."""
    for url_path, methods in sorted(spec["paths"].items()):
        for method, operation in methods.items():
            if method in METHODS:
                yield method.upper(), url_path, operation


def auth_of(operation: dict) -> str:
    """The auth level, which lives in the description prose, not in `security`.

    Every operation declares `security` regardless of whether credentials are
    required, because FastAPI emits it for optional (auto_error=False) auth too.
    """
    match = AUTH_RE.search(operation.get("description") or "")
    return match.group(1).strip().strip("*").strip() if match else "(unspecified)"


def rates_of(operation: dict) -> dict[str, str]:
    """Rate limits appear in two shapes; missing the second reads as 'no limit'."""
    description = operation.get("description") or ""
    # Shape 1, grouped by auth:  When `auth=anon`:\n  - 30/1min
    grouped = dict(RATE_RE.findall(description))
    if grouped:
        return grouped
    # Shape 2, flat bullets:  **Rate limits:**\n- 15/1min per user
    _, _, tail = description.partition("**Rate limits:**")
    if not tail:
        return {}
    rates: dict[str, str] = {}
    for line in tail.splitlines():
        line = line.strip()
        if line.startswith("**"):  # next section
            break
        match = RATE_FLAT_RE.match(line)
        if match:
            value, scope = match.group(1), (match.group(2) or "all").strip()
            rates.setdefault(scope, value)
    return rates


def resolve(spec: dict, node: Any) -> Any:
    """Follow a single $ref, if this node is one."""
    if isinstance(node, dict) and "$ref" in node:
        target = spec
        for part in node["$ref"].lstrip("#/").split("/"):
            target = target[part]
        return target
    return node


def type_name(spec: dict, schema: dict) -> str:
    """A short type label, collapsing the 3.1 `anyOf [T, null]` nullable idiom."""
    schema = resolve(spec, schema)
    if "$ref" in schema:
        return schema["$ref"].rsplit("/", 1)[-1]
    if "anyOf" in schema or "oneOf" in schema:
        options = schema.get("anyOf") or schema["oneOf"]
        names = [type_name(spec, o) for o in options]
        non_null = [n for n in names if n != "null"]
        joined = "|".join(non_null) or "null"
        return f"{joined}|null" if "null" in names and non_null else joined
    if schema.get("type") == "array":
        return f"array[{type_name(spec, schema.get('items', {}))}]"
    if "enum" in schema:
        return "enum(" + ",".join(str(v) for v in schema["enum"]) + ")"
    return schema.get("type", "any")


def render_schema(spec: dict, schema: dict, depth: int, indent: int = 2,
                  seen: frozenset[str] = frozenset()) -> list[str]:
    """Render a schema as an indented field tree, guarding against ref cycles."""
    raw = schema
    schema = resolve(spec, schema)
    pad = " " * indent

    if isinstance(raw, dict) and "$ref" in raw:
        name = raw["$ref"].rsplit("/", 1)[-1]
        if name in seen:
            return [f"{pad}<recursive {name}>"]
        seen = seen | {name}

    if depth <= 0:
        return []
    if schema.get("type") == "array":
        return render_schema(spec, schema.get("items", {}), depth, indent, seen)

    lines: list[str] = []
    required = set(schema.get("properties") and schema.get("required", []) or [])
    for field, sub in (schema.get("properties") or {}).items():
        flag = "" if field in required else " (optional)"
        lines.append(f"{pad}{field}: {type_name(spec, sub)}{flag}")
        nested = resolve(spec, sub)
        if nested.get("type") == "array":
            nested = resolve(spec, nested.get("items", {}))
        if nested.get("properties") or "$ref" in (sub if isinstance(sub, dict) else {}):
            lines.extend(render_schema(spec, sub, depth - 1, indent + 2, seen))
    return lines


def describe(spec: dict, method: str, url_path: str, operation: dict, depth: int) -> None:
    print(f"{method} {url_path}  -- {operation.get('summary', '')}")
    print(f"Auth: {auth_of(operation)}")

    rates = rates_of(operation)
    if rates:
        print("Rate: " + "  ".join(f"{k}={v}" for k, v in rates.items()))

    params = operation.get("parameters") or []
    if params:
        print("Params:")
        for param in params:
            param = resolve(spec, param)
            schema = param.get("schema", {})
            default = schema.get("default")
            suffix = "required" if param.get("required") else f"default={default!r}"
            print(f"  {param['name']:20} {param.get('in', ''):8} "
                  f"{type_name(spec, schema):24} {suffix}")

    body = operation.get("requestBody")
    if body:
        body = resolve(spec, body)
        for media, content in (body.get("content") or {}).items():
            print(f"Body ({media}):")
            for line in render_schema(spec, content.get("schema", {}), depth):
                print(line)

    print("Responses:")
    for code, response in sorted((operation.get("responses") or {}).items()):
        response = resolve(spec, response)
        content = response.get("content") or {}
        schema = (content.get("application/json") or {}).get("schema")
        label = type_name(spec, schema) if schema else response.get("description", "")
        print(f"  {code} -> {label}")
        if schema:
            for line in render_schema(spec, schema, depth, indent=6):
                print(line)


def cmd_find(args: argparse.Namespace) -> None:
    spec = load_spec()
    terms = [t.lower() for t in args.terms]
    hits = 0
    for method, url_path, operation in iter_operations(spec):
        haystack = " ".join([
            url_path, method, operation.get("summary", "") or "",
            operation.get("description", "") or "",
            " ".join(operation.get("tags", []) or []),
        ]).lower()
        if all(t in haystack for t in terms):
            hits += 1
            print(f"{method:6} {url_path:52} {operation.get('summary', '')}")
    if not hits:
        print(f"No operations matched: {' '.join(args.terms)}")


def cmd_list(args: argparse.Namespace) -> None:
    spec = load_spec()
    for method, url_path, operation in iter_operations(spec):
        auth = auth_of(operation)
        if args.auth and args.auth.lower() not in auth.lower():
            continue
        if args.path and args.path.lower() not in url_path.lower():
            continue
        print(f"{method:6} {url_path:52} {auth}")


def normalize_path(raw: str) -> str:
    """Undo the MSYS rewrite Git Bash applies to a leading-slash argument.

    `apiref.py show /api/v2/galleries` arrives as `C:/Program Files/Git/api/v2/...`,
    so anchor on the real prefix instead of trusting the argument verbatim.
    """
    index = raw.find("/api/v2")
    return raw[index:] if index > 0 else raw


def cmd_show(args: argparse.Namespace) -> None:
    spec = load_spec()
    wanted_method = args.method.upper() if args.method else None
    wanted_path = normalize_path(args.path)
    matches = [
        (m, p, o) for m, p, o in iter_operations(spec)
        if wanted_path.lower() in p.lower() and (not wanted_method or m == wanted_method)
    ]
    if not matches:
        sys.exit(f"No operation matching {args.method or ''} {wanted_path}".strip())
    # Prefer an exact path match when the substring also hits longer paths.
    exact = [m for m in matches if m[1].lower() == wanted_path.lower()]
    for index, (method, url_path, operation) in enumerate(exact or matches):
        if index:
            print()
        describe(spec, method, url_path, operation, args.depth)


def cmd_schema(args: argparse.Namespace) -> None:
    spec = load_spec()
    schemas = spec.get("components", {}).get("schemas", {})
    names = [n for n in schemas if args.name.lower() in n.lower()]
    if not names:
        sys.exit(f"No schema matching {args.name}")
    for index, name in enumerate(sorted(names)):
        if index:
            print()
        print(f"{name}:")
        for line in render_schema(spec, schemas[name], args.depth):
            print(line)


def cmd_refresh(_: argparse.Namespace) -> None:
    request = urllib.request.Request(SPEC_URL, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        spec = json.load(response)
    with open(SPEC_FILE, "w", encoding="utf-8") as writer:
        json.dump(spec, writer, indent=1, sort_keys=True)
    operations = sum(1 for _ in iter_operations(spec))
    print(f"Wrote {SPEC_FILE}")
    print(f"version={spec['info'].get('version')} paths={len(spec['paths'])} ops={operations}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    subparsers = parser.add_subparsers(dest="command", required=True)

    find = subparsers.add_parser("find", help="search operations by keyword (AND across terms)")
    find.add_argument("terms", nargs="+")
    find.set_defaults(func=cmd_find)

    listing = subparsers.add_parser("list", help="list operations with their auth level")
    listing.add_argument("--auth", help="filter by auth level substring, e.g. public, staff")
    listing.add_argument("--path", help="filter by path substring")
    listing.set_defaults(func=cmd_list)

    show = subparsers.add_parser("show", help="full detail for an operation")
    show.add_argument("method", nargs="?", help="GET, POST, ... (optional)")
    show.add_argument("path")
    show.add_argument("--depth", type=int, default=DEFAULT_DEPTH)
    show.set_defaults(func=cmd_show)

    schema = subparsers.add_parser("schema", help="render a component schema")
    schema.add_argument("name")
    schema.add_argument("--depth", type=int, default=DEFAULT_DEPTH)
    schema.set_defaults(func=cmd_schema)

    refresh = subparsers.add_parser("refresh", help="re-download the spec from nhentai.net")
    refresh.set_defaults(func=cmd_refresh)

    args = parser.parse_args()
    # `show GET /path` parses as method+path; `show /path` leaves method holding the path.
    if args.command == "show" and args.method and args.method.startswith("/"):
        args.method, args.path = None, args.method
    args.func(args)


if __name__ == "__main__":
    main()
