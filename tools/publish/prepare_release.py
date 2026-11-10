#!/usr/bin/env python3
"""把编译好的 release APK 变成一次完整发布。

用法(仓库根目录):
    python3 tools/publish/prepare_release.py

做四件事:
  1. 从 app/build/outputs/apk/{public,personal}/release/ 收集 APK,复制到 dist/ 并改成对外名字;
  2. 计算每个包的字节数与 SHA-256,写 dist/checksums.txt 与 Release 说明;
  3. 生成官网两份版本清单 site/api/latest.json、latest-personal.json(字段兼容 GitHub releases/latest);
  4. 用 tools/publish/download_template.html 渲染 site/download/index.html。

只处理本机已有的 APK,不会联网、不会上传、不会改签名。发版前请确认
`apksigner verify` 通过;换证书时用 --signer 更新页面上的证书指纹。
"""

import argparse
import hashlib
import json
import re
import shutil
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APK_ROOT = ROOT / "app" / "build" / "outputs" / "apk"
DIST = ROOT / "dist"
SITE = ROOT / "site"
TEMPLATE = ROOT / "tools" / "publish" / "download_template.html"

REPO = "cnwhli/nuoyi"
SITE_URL = "https://noy.127068.xyz"
DEFAULT_SIGNER = "97:BF:C9:61:14:CA:00:E7:87:51:10:FB:CF:CD:4A:90:A4:68:99:AC:00:64:BC:B7:B5:96:3C:6D:85:E1:2B:06"

NOTES = """### 本次更新

- 品牌与唤醒词统一为「诺伊」；公版唤醒词固定不可自定义，个人版可自定义并支持本地常驻热词。
- 任何入口问「诺伊你是谁」，都由本地代码固定回答「我是李万禧的女儿，数字女儿」，不经过模型、不联网、不消耗额度。
- 新增免手语音入口、系统数字助手会话、半双工关麦与敏感动作二次确认。
- 新增额度感知路由：官方公开免费档与自有 Key 之间自动切换、冷却与到期自愈。
- 发布渠道切到官网，App 内更新、下载域名白名单与签名校验全部改为自有链路。

### 下载与校验

官网下载页：{site}/download/ 。普通用户选「公版 · 全架构」；arm64 设备可选体积更小的专用包。
个人版仅供自用侧载，包名与公版不同，可并排安装。

安装前请核对下载页给出的 SHA-256 与签名证书指纹。App 内更新会自动校验
大小、摘要、包名、版本与签名证书，任何一项不符都会拒绝安装。

本项目基于 wangzan101/Mika（Apache-2.0）二次开发，保留原版权与许可声明，
详见仓库 LICENSE、NOTICE 与 licenses 目录。
""".format(site=SITE_URL)

EDITIONS = {
    "public": {
        "title": "公版",
        "package": "com.nuoyi.agent",
        "note": "对外分发，唤醒词固定为「诺伊」，无常驻热词",
    },
    "personal": {
        "title": "个人版",
        "package": "com.nuoyi.agent.personal",
        "note": "自用侧载，可自定义唤醒词与本地常驻热词",
    },
}


def version_from_gradle() -> str:
    text = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not m:
        raise SystemExit("找不到 versionName，请用 --version 指定")
    return m.group(1)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def collect(flavor: str, version: str, tag: str) -> list[dict]:
    folder = APK_ROOT / flavor / "release"
    files = sorted(folder.glob("*.apk"))
    if not files:
        raise SystemExit(f"没有找到 {folder} 下的 release APK，请先编译 assemble{flavor.capitalize()}Release")
    picked: dict[str, Path] = {}
    for f in files:
        abi = "arm64" if "arm64" in f.name.lower() else "universal"
        picked[abi] = f
    if "universal" not in picked:
        raise SystemExit(f"{folder} 里没有全架构包")

    out = []
    for abi, src in sorted(picked.items()):
        suffix = "-arm64" if abi == "arm64" else ""
        name = f"Nuoyi-v{version}-{flavor}{suffix}.apk"
        dst = DIST / name
        shutil.copy2(src, dst)
        digest = "sha256:" + sha256(dst)
        out.append({
            "edition": flavor,
            "abi": abi,
            "name": name,
            "path": dst,
            "size": dst.stat().st_size,
            "digest": digest,
            "url": f"https://github.com/{REPO}/releases/download/{tag}/{name}",
            "title": f"{EDITIONS[flavor]['title']} · {'arm64 专用' if abi == 'arm64' else '全架构'}",
            "abiLabel": "arm64-v8a 专用" if abi == "arm64" else "全架构 universal",
            "packageName": EDITIONS[flavor]["package"],
            "note": EDITIONS[flavor]["note"],
            "featured": flavor == "public" and abi == "universal",
        })
    return out


def manifest(version: str, tag: str, day: str, apks: list[dict]) -> dict:
    return {
        "tag_name": tag,
        "name": f"诺伊 {version}",
        "body": NOTES,
        "published_at": f"{day}T00:00:00Z",
        "html_url": f"{SITE_URL}/download/",
        "prerelease": False,
        "assets": [
            {"name": a["name"], "browser_download_url": a["url"], "size": a["size"], "digest": a["digest"]}
            for a in apks
        ],
    }


def main() -> None:
    p = argparse.ArgumentParser(description="生成诺伊官网清单、下载页与 Release 附件")
    p.add_argument("--version", default=version_from_gradle(), help="版本号，默认读 app/build.gradle.kts")
    p.add_argument("--date", default=date.today().isoformat(), help="发布日期 yyyy-MM-dd")
    p.add_argument("--tag", default=None, help="Release tag，默认 v<版本号>")
    p.add_argument("--signer", default=DEFAULT_SIGNER, help="签名证书 SHA-256 指纹（轮换证书时必改）")
    args = p.parse_args()
    version, day = args.version, args.date
    tag = args.tag or f"v{version}"
    release_url = f"https://github.com/{REPO}/releases/tag/{tag}"

    DIST.mkdir(parents=True, exist_ok=True)
    (SITE / "api").mkdir(parents=True, exist_ok=True)
    apks = collect("public", version, tag) + collect("personal", version, tag)

    for edition in ("public", "personal"):
        subset = [a for a in apks if a["edition"] == edition]
        filename = "latest.json" if edition == "public" else "latest-personal.json"
        (SITE / "api" / filename).write_text(
            json.dumps(manifest(version, tag, day, subset), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    checksum_lines = [f"{a['digest'].split(':', 1)[1]}  {a['name']}" for a in apks]
    (DIST / "checksums.txt").write_text("\n".join(checksum_lines) + "\n", encoding="utf-8")
    (DIST / "RELEASE_NOTES.md").write_text(NOTES, encoding="utf-8")
    (DIST / "upload.txt").write_text("\n".join(str(a["path"]) for a in apks) + "\n", encoding="utf-8")

    page_data = {k: v for k, v in {
        "version": version, "date": day, "tag": tag, "apks": [
            {k: v for k, v in a.items() if k != "path"} for a in apks
        ],
    }.items()}
    replacements = {
        "__VERSION__": version,
        "__DATE__": day,
        "__RELEASE_URL__": release_url,
        "__SIGNER__": args.signer,
        "__RELEASE_DATA__": json.dumps(page_data, ensure_ascii=False, separators=(",", ":")),
    }
    html = TEMPLATE.read_text(encoding="utf-8")
    for key, value in replacements.items():
        html = html.replace(key, value)
    (SITE / "download" / "index.html").write_text(html, encoding="utf-8")

    print(f"版本 {version} / tag {tag} / 日期 {day}")
    for a in apks:
        print(f"  {a['name']:46s} {a['size'] / 1048576:7.1f} MB  {a['digest'][:22]}…")
    print("已生成: site/api/latest.json, site/api/latest-personal.json, site/download/index.html")
    print("已生成: dist/checksums.txt, dist/RELEASE_NOTES.md, dist/upload.txt")


if __name__ == "__main__":
    main()
