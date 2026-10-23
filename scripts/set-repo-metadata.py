#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""一条命令补全 GitHub 仓库的 Description 与 Topics。

为什么单独做成脚本：GitHub 的仓库元信息只能通过 api.github.com 修改，
而有些网络环境下 github.com 可访问、api.github.com 被代理规则拦截。
把文本固化在仓库里，换网络/换机器都能一键重设，不用手打。

用法：
    python scripts/set-repo-metadata.py                 # 用 gh CLI 的登录态
    GITHUB_TOKEN=ghp_xxx python scripts/set-repo-metadata.py
    python scripts/set-repo-metadata.py --repo owner/name

注意：Token 只从环境变量或 gh CLI 读取，本脚本不会写入任何文件。
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.error
import urllib.request

DESCRIPTION = (
    "豆包 / ChatGPT 对话导出为 Markdown + SQLite 的本地服务："
    "快照式全量·增量导出、RabbitMQ 并发抓取、"
    "FTS5 中文全文检索（1~2 字也走索引）、附件落盘。Spring Boot 4 + 原生 HTML/JS。"
)

TOPICS = [
    "spring-boot", "java", "sqlite", "fts5", "full-text-search", "chinese",
    "chatgpt", "doubao", "data-export", "markdown", "rabbitmq", "local-first", "archival",
]


def token_from_gh():
    if not shutil.which("gh"):
        return None
    try:
        out = subprocess.run(["gh", "auth", "token"], capture_output=True, text=True, timeout=20)
        return (out.stdout or "").strip() or None
    except Exception:
        return None


def call(method, url, body, tok):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    req.add_header("User-Agent", "chat-exporter-metadata")
    if data:
        req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + tok)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read().decode("utf-8", "ignore") or "{}")
    except urllib.error.HTTPError as e:
        return e.code, {"message": e.read().decode("utf-8", "ignore")[:300]}
    except Exception as e:  # 网络/代理问题
        return -1, {"message": f"{type(e).__name__}: {e}"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default="wenliangliu05/chat-exporter")
    args = ap.parse_args()

    tok = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN") or token_from_gh()
    if not tok:
        print("✗ 找不到凭据：请先 `gh auth login`，或设置 GITHUB_TOKEN 环境变量")
        return 2

    st, body = call("PATCH", f"https://api.github.com/repos/{args.repo}",
                    {"description": DESCRIPTION, "has_issues": True, "has_wiki": False}, tok)
    print(f"description -> {st} {body.get('message', '')}".rstrip())
    st2, body2 = call("PUT", f"https://api.github.com/repos/{args.repo}/topics",
                      {"names": TOPICS}, tok)
    print(f"topics      -> {st2} {body2.get('message', '')}".rstrip())

    if st == 200 and st2 == 200:
        print("\n✓ 已更新")
        print("  " + DESCRIPTION)
        print("  topics: " + ", ".join(TOPICS))
        return 0
    print("\n失败提示：若 api.github.com 不通（github.com 却正常），多半是代理规则拦截了它。")
    print("可临时指定代理：  HTTPS_PROXY=http://127.0.0.1:<端口> python scripts/set-repo-metadata.py")
    return 1


if __name__ == "__main__":
    sys.exit(main())
