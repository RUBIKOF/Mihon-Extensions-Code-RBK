#!/usr/bin/env python3

import argparse
import gzip
import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

from google.protobuf import json_format

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from index_pb2 import Index  # noqa: E402


def run(*args: str, cwd: Path | None = None) -> str:
    result = subprocess.run(
        list(args),
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=True,
    )
    return result.stdout.strip()


def first_existing(paths: list[Path]) -> Path | None:
    for path in paths:
        if path.exists():
            return path
    return None


def find_latest(module: Path, pattern: str) -> Path | None:
    candidates = [
        p for p in module.rglob(pattern)
        if p.is_file()
        and "debug" not in p.name.lower()
        and "unsigned" not in p.name.lower()
    ]
    return max(candidates, key=lambda p: p.stat().st_mtime) if candidates else None


def read_metadata(module: Path) -> dict:
    meta = find_latest(module, "keiyoushi-source-info.json")
    if not meta:
        raise FileNotFoundError(
            f"No keiyoushi-source-info.json found for {module}. "
            "The release build must generate extension metadata."
        )
    return json.loads(meta.read_text(encoding="utf-8"))


def load_index(path: Path, *, name: str, badge: str, signing_key: str, contact_url: str) -> Index:
    index = Index()

    if path.exists() and path.stat().st_size:
        raw = path.read_bytes()
        try:
            raw = gzip.decompress(raw)
        except gzip.BadGzipFile:
            pass
        index.ParseFromString(raw)

        # Las variables del repositorio privado son la fuente de verdad
        # para la metadata global del índice, incluso si index.pb ya existe.
        index.name = name
        index.badgeLabel = badge
        index.signingKey = signing_key
        index.contact.website = contact_url
        return index

    index.name = name
    index.badgeLabel = badge
    index.signingKey = signing_key
    index.contact.website = contact_url
    index.extensionList.SetInParent()
    return index


def icon_url_for(module: Path, old_extension, metadata: dict) -> str:
    # Para una extensión ya publicada, conservar su iconUrl automáticamente.
    if old_extension is not None and old_extension.resources.iconUrl:
        return old_extension.resources.iconUrl

    # Para una extensión nueva, usa un archivo opcional:
    # src/<lang>/<ext>/icon-url.txt
    icon_file = module / "icon-url.txt"
    if icon_file.exists():
        value = icon_file.read_text(encoding="utf-8").strip()
        if value:
            return value

    # Si algún metadata futuro ya incluye iconUrl, aprovecharlo.
    value = metadata.get("iconUrl")
    if isinstance(value, str) and value.strip():
        return value.strip()

    raise RuntimeError(
        f"{module}: this is a new extension and no icon URL is known. "
        f"Create {icon_file} containing only the public image URL."
    )


def normalize_warning(value) -> str:
    """
    Normalize the content warning emitted by Gradle metadata.

    Supported numeric values:
      0 = UNSPECIFIED
      1 = SAFE
      2 = MIXED
      3 = NSFW

    Text values are also accepted for compatibility.
    """
    if value is None:
        return "CONTENT_WARNING_UNSPECIFIED"

    if isinstance(value, bool):
        return "CONTENT_WARNING_UNSPECIFIED"

    if isinstance(value, int):
        return {
            0: "CONTENT_WARNING_UNSPECIFIED",
            1: "CONTENT_WARNING_SAFE",
            2: "CONTENT_WARNING_MIXED",
            3: "CONTENT_WARNING_NSFW",
        }.get(value, "CONTENT_WARNING_UNSPECIFIED")

    text = str(value).strip().upper()

    if text.isdigit():
        return {
            "0": "CONTENT_WARNING_UNSPECIFIED",
            "1": "CONTENT_WARNING_SAFE",
            "2": "CONTENT_WARNING_MIXED",
            "3": "CONTENT_WARNING_NSFW",
        }.get(text, "CONTENT_WARNING_UNSPECIFIED")

    if "NSFW" in text:
        return "CONTENT_WARNING_NSFW"
    if "MIXED" in text:
        return "CONTENT_WARNING_MIXED"
    if "SAFE" in text:
        return "CONTENT_WARNING_SAFE"
    if "UNSPECIFIED" in text:
        return "CONTENT_WARNING_UNSPECIFIED"

    return "CONTENT_WARNING_UNSPECIFIED"


def warning_number(value: str) -> int:
    return {
        "CONTENT_WARNING_UNSPECIFIED": 0,
        "CONTENT_WARNING_SAFE": 1,
        "CONTENT_WARNING_MIXED": 2,
        "CONTENT_WARNING_NSFW": 3,
    }[value]


def source_list(metadata: dict) -> list[dict]:
    sources = metadata.get("sources") or metadata.get("sourceList") or []

    # Algunos builds guardan una sola fuente en campos de nivel superior.
    if not sources and metadata.get("source"):
        sources = [metadata["source"]]

    if not sources:
        sid = metadata.get("sourceId") or metadata.get("id")
        sname = metadata.get("name")
        slang = metadata.get("language") or metadata.get("lang")
        surl = metadata.get("baseUrl") or metadata.get("homeUrl") or ""
        if sid is not None and sname and slang:
            sources = [{"id": sid, "name": sname, "language": slang, "homeUrl": surl}]

    if not sources:
        raise RuntimeError("No source metadata found in keiyoushi-source-info.json")

    normalized = []
    for src in sources:
        normalized.append(
            {
                "id": int(src.get("id") or src.get("sourceId")),
                "name": src.get("name"),
                "language": src.get("language") or src.get("lang"),
                "homeUrl": src.get("homeUrl") or src.get("baseUrl") or "",
                "mirrorUrls": src.get("mirrorUrls") or [],
                "message": src.get("message"),
            }
        )
    return normalized


def metadata_value(metadata: dict, *keys, required: bool = True):
    for key in keys:
        value = metadata.get(key)
        if value is not None and value != "":
            return value
    if required:
        raise RuntimeError(f"Missing metadata field. Tried: {', '.join(keys)}")
    return None


def build_extension_json(metadata: dict, *, apk_url: str, jar_url: str, icon_url: str) -> dict:
    package_name = metadata_value(metadata, "packageName", "pkg")
    name = metadata_value(metadata, "name")
    extension_lib = str(metadata_value(metadata, "extensionLib", "libVersion"))
    version_code = int(metadata_value(metadata, "versionCode", "code"))
    version_name = str(metadata_value(metadata, "versionName", "version"))

    warning = normalize_warning(metadata.get("contentWarning"))

    result = {
        "name": name,
        "packageName": package_name,
        "resources": {
            "apkUrl": apk_url,
            "iconUrl": icon_url,
        },
        "extensionLib": extension_lib,
        "versionCode": str(version_code),
        "versionName": version_name,
        "contentWarning": warning,
        "sources": source_list(metadata),
    }

    # Keiyoushi's proto currently has jarUrl as field 501.
    if jar_url:
        result["resources"]["jarUrl"] = jar_url

    return result


def package_from_metadata(metadata: dict) -> str:
    return str(metadata_value(metadata, "packageName", "pkg"))


def slug_from_package(package_name: str) -> str:
    tail = package_name.rsplit(".", 1)[-1]
    return re.sub(r"[^a-zA-Z0-9._-]+", "-", tail).strip("-").lower()


def gh_release(repository: str, tag: str, title: str, apk: Path, jar: Path | None) -> None:
    files = [str(apk)]
    if jar:
        files.append(str(jar))

    # Si ya existe el tag, reemplazamos assets; si no, creamos la Release.
    exists = subprocess.run(
        ["gh", "release", "view", tag, "--repo", repository],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    ).returncode == 0

    if exists:
        run(
            "gh", "release", "upload", tag, *files,
            "--repo", repository,
            "--clobber",
        )
    else:
        run(
            "gh", "release", "create", tag, *files,
            "--repo", repository,
            "--title", title,
            "--notes", f"Automated release for {title}.",
        )


def release_date_for(extension, repository: str) -> str:
    """
    Return the publication date of the exact GitHub Release used by the
    extension currently stored in index.pb.

    Tag format must match gh_release():
        <slug-from-package>-v<versionCode>

    If the release cannot be found or its date cannot be parsed, return "—"
    without aborting the README update.
    """
    try:
        package_name = str(extension.packageName).strip()
        version_code = str(extension.versionCode).strip()

        if not package_name or not version_code:
            return "—"

        slug = slug_from_package(package_name)
        tag = f"{slug}-v{version_code}"

        published_at = run(
            "gh",
            "release",
            "view",
            tag,
            "--repo",
            repository,
            "--json",
            "publishedAt",
            "--jq",
            ".publishedAt",
        ).strip()

        if not published_at:
            return "—"

        parsed = datetime.fromisoformat(
            published_at.replace("Z", "+00:00")
        )

        return parsed.strftime("%d/%m/%Y")

    except (
        subprocess.CalledProcessError,
        ValueError,
        TypeError,
        AttributeError,
    ):
        return "—"



README_TABLE_START = "<!-- RBK_EXTENSIONS_TABLE_START -->"
README_TABLE_END = "<!-- RBK_EXTENSIONS_TABLE_END -->"


LANGUAGE_DISPLAY = {
    "es": "🇪🇸 Español",
    "en": "🇬🇧 Inglés",
    "ja": "🇯🇵 Japonés",
    "fr": "🇫🇷 Francés",
    "de": "🇩🇪 Alemán",
    "ru": "🇷🇺 Ruso",
    "ko": "🇰🇷 Coreano",
    "pt": "🇵🇹 Portugués",
    "it": "🇮🇹 Italiano",
    "zh": "🇨🇳 Chino",
    "ar": "🇸🇦 Árabe",
    "tr": "🇹🇷 Turco",
    "id": "🇮🇩 Indonesio",
    "vi": "🇻🇳 Vietnamita",
    "pl": "🇵🇱 Polaco",
    "nl": "🇳🇱 Neerlandés",
    "th": "🇹🇭 Tailandés",
}


def extension_languages(extension) -> list[str]:
    languages = {
        str(source.language).strip().lower()
        for source in extension.sources
        if str(source.language).strip()
    }
    return sorted(languages)


def coverage_for(extension) -> tuple[str, bool]:
    languages = extension_languages(extension)

    # "all" representa una fuente global/multi-idioma.
    if "all" in languages or len(languages) > 1:
        return "🌐 Multi-idioma", True

    if not languages:
        return "❔ Sin idioma", False

    lang = languages[0]
    return LANGUAGE_DISPLAY.get(lang, f"🌐 {lang.upper()}"), False


def markdown_cell(value: str) -> str:
    return str(value).replace("|", r"\|").replace("\n", " ").strip()


def replace_marked_section(
    text: str,
    start_marker: str,
    end_marker: str,
    content: str,
) -> str:
    pattern = re.compile(
        re.escape(start_marker) + r".*?" + re.escape(end_marker),
        re.DOTALL,
    )

    if not pattern.search(text):
        raise RuntimeError(
            f"README marker block not found: {start_marker} ... {end_marker}"
        )

    replacement = f"{start_marker}\n{content.rstrip()}\n{end_marker}"
    return pattern.sub(lambda _: replacement, text, count=1)


def update_public_readme(
    readme_path: Path,
    index: Index,
    *,
    public_repository: str,
) -> None:
    if not readme_path.exists():
        raise FileNotFoundError(
            f"Public README not found: {readme_path}. "
            "Add README.md with the RBK marker blocks before publishing."
        )

    extensions = list(index.extensionList.extensions)
    extension_count = len(extensions)

    extensions_badge = (
        "[![Extensions]"
        f"(https://img.shields.io/badge/EXTENSIONS-{extension_count}-22c55e"
        "?style=for-the-badge)]"
        f"(https://github.com/{public_repository}/releases)"
    )

    extensions_count_line = (
        f"**{extension_count} extensiones · múltiples idiomas · "
        "actualizaciones desde Mihon**"
    )

    rows = []
    for extension in extensions:
        coverage, is_multi = coverage_for(extension)

        updated_at = release_date_for(
            extension,
            public_repository,
        )

        rows.append(
            (
                0 if is_multi else 1,
                extension.name.casefold(),
                extension.name,
                coverage,
                extension.versionName,
                updated_at,
            )
        )

    rows.sort(key=lambda item: (item[0], item[1]))

    table_lines = [
        "| Extensión | Cobertura | Versión | Actualización |",
        "|---|---|---|---|",
    ]

    for _, _, name, coverage, version_name, updated_at in rows:
        table_lines.append(
            "| "
            + " | ".join(
                [
                    markdown_cell(name),
                    markdown_cell(coverage),
                    markdown_cell(version_name),
                    markdown_cell(updated_at),
                ]
            )
            + " |"
        )

    readme = readme_path.read_text(encoding="utf-8")
    badge_pattern = re.compile(
        r"\[!\[Extensions\]\(https://img\.shields\.io/badge/EXTENSIONS-\d+-22c55e"
        r"\?style=for-the-badge\)\]\(https://github\.com/[^)]+/releases\)"
    )
    readme, badge_replacements = badge_pattern.subn(
        extensions_badge,
        readme,
        count=1,
    )
    if badge_replacements != 1:
        raise RuntimeError("Extensions badge not found in public README.")

    count_pattern = re.compile(
        r"\*\*\d+ extensiones · múltiples idiomas · actualizaciones desde Mihon\*\*"
    )
    readme, count_replacements = count_pattern.subn(
        extensions_count_line,
        readme,
        count=1,
    )
    if count_replacements != 1:
        raise RuntimeError("Extensions count line not found in public README.")

    readme = replace_marked_section(
        readme,
        README_TABLE_START,
        README_TABLE_END,
        "\n".join(table_lines),
    )

    readme_path.write_text(readme, encoding="utf-8")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--modules-file", required=True)
    parser.add_argument("--public-dir", required=True)
    parser.add_argument("--public-repository", required=True)
    parser.add_argument("--store-name", required=True)
    parser.add_argument("--store-badge", required=True)
    parser.add_argument("--contact-url", required=True)
    parser.add_argument("--signing-key", required=True)
    args = parser.parse_args()

    public_dir = Path(args.public_dir).resolve()
    index_path = public_dir / "index.pb"
    readme_path = public_dir / "README.md"

    index = load_index(
        index_path,
        name=args.store_name,
        badge=args.store_badge,
        signing_key=args.signing_key,
        contact_url=args.contact_url,
    )

    modules = [
        Path(line.strip())
        for line in Path(args.modules_file).read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]

    # Facilita buscar y reemplazar la extensión existente por packageName.
    existing = {
        ext.packageName: ext
        for ext in index.extensionList.extensions
    }

    updated_json_by_pkg: dict[str, dict] = {}

    for module in modules:
        metadata = read_metadata(module)
        package_name = package_from_metadata(metadata)
        version_code = int(metadata_value(metadata, "versionCode", "code"))
        version_name = str(metadata_value(metadata, "versionName", "version"))
        ext_name = str(metadata_value(metadata, "name"))

        apk = find_latest(module, "*.apk")
        jar = find_latest(module, "*.jar")

        if not apk:
            raise FileNotFoundError(f"No release APK found for {module}")

        slug = slug_from_package(package_name)
        tag = f"{slug}-v{version_code}"

        gh_release(
            args.public_repository,
            tag,
            f"{ext_name} {version_name}",
            apk,
            jar,
        )

        base = f"https://github.com/{args.public_repository}/releases/download/{tag}"
        apk_url = f"{base}/{apk.name}"
        jar_url = f"{base}/{jar.name}" if jar else ""

        icon_url = icon_url_for(
            module,
            existing.get(package_name),
            metadata,
        )

        updated_json_by_pkg[package_name] = build_extension_json(
            metadata,
            apk_url=apk_url,
            jar_url=jar_url,
            icon_url=icon_url,
        )

    # Serializamos el índice actual a dict, reemplazamos solo los paquetes actualizados
    # y volvemos a parsearlo usando el mismo proto.
    current = json_format.MessageToDict(
        index,
        preserving_proto_field_name=False,
        always_print_fields_with_no_presence=False,
    )

    ext_list = current.setdefault("extensionList", {}).setdefault("extensions", [])
    old_by_pkg = {item["packageName"]: item for item in ext_list}

    old_by_pkg.update(updated_json_by_pkg)

    # Orden estable para que index.pb no cambie innecesariamente.
    current["extensionList"]["extensions"] = [
        old_by_pkg[pkg] for pkg in sorted(old_by_pkg)
    ]

    rebuilt = json_format.ParseDict(current, Index())

    raw = rebuilt.SerializeToString(deterministic=True)
    index_path.write_bytes(gzip.compress(raw, mtime=0))

    update_public_readme(
        readme_path,
        rebuilt,
        public_repository=args.public_repository,
    )

    print(f"Updated: {index_path}")
    print(f"Updated: {readme_path}")
    for pkg in sorted(updated_json_by_pkg):
        print(f"Published: {pkg}")


if __name__ == "__main__":
    main()
