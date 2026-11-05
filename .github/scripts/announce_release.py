#!/usr/bin/env python3
"""Announces a release in the Discord updates channel - only once it's really downloadable on Modrinth.

Waits (up to WAIT_MINUTES) until every upload in .github/modrinth.json exists on Modrinth as a
listed version with a downloadable file, then posts one embed with link buttons. If Modrinth never
gets there, nothing is posted and the job fails.

Env: VERSION (e.g. 1.1.10), DISCORD_WEBHOOK, PING ("@everyone", a role id, or empty),
     REPO (owner/name), DRY_RUN=1 to print the message instead of sending it, WAIT_MINUTES (15).
Run from the repository root at the release tag (reads CHANGELOG.md and .github/modrinth.json).
Discord API: https://discord.com/developers/docs/resources/webhook#execute-webhook
Modrinth API: https://docs.modrinth.com/api/
"""
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

UA = "TheCommandCraft/Vanishpp release-announce (github.com/TheCommandCraft/Vanishpp)"
API = "https://api.modrinth.com/v2"
COLOR = 0x1BD96A  # Modrinth green
LOADER_NAMES = {"paper": "Paper", "folia": "Folia", "purpur": "Purpur", "spigot": "Spigot",
                "bukkit": "Bukkit", "velocity": "Velocity"}


def request(url, data=None, method=None, headers=None):
    req = urllib.request.Request(url, data=data, method=method, headers={"User-Agent": UA, **(headers or {})})
    with urllib.request.urlopen(req, timeout=30) as resp:
        return resp.status, resp.read()


def get_json(url):
    return json.loads(request(url)[1])


def wait_for_modrinth(cfg, version, minutes):
    """Returns {upload index: modrinth version} once all uploads are listed and downloadable."""
    deadline = time.time() + minutes * 60
    wanted = [set(u["loaders"]) for u in cfg["uploads"]]
    while True:
        found, missing = {}, []
        try:
            versions = get_json(f"{API}/project/{cfg['project_id']}/version?include_changelog=false")
            for i, loaders in enumerate(wanted):
                match = next((v for v in versions if v["version_number"] == version
                              and set(v["loaders"]) == loaders), None)
                if match is None:
                    missing.append(f"{cfg['uploads'][i]['name']}: not on Modrinth yet")
                elif match.get("status") != "listed":
                    missing.append(f"{match['name']}: status {match.get('status')}")
                else:
                    file = next((f for f in match["files"] if f.get("primary")), match["files"][0] if match["files"] else None)
                    if file is None:
                        missing.append(f"{match['name']}: no file")
                    elif request(file["url"], method="HEAD")[0] != 200:
                        missing.append(f"{match['name']}: file not downloadable yet")
                    else:
                        found[i] = match
        except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as e:
            missing.append(f"Modrinth API: {e}")
        if not missing:
            return found
        if time.time() > deadline:
            sys.exit(f"::error::Gave up after {minutes} minutes, nothing was posted: " + "; ".join(missing))
        print("Waiting for Modrinth: " + "; ".join(missing), flush=True)
        time.sleep(20)


def changelog_section(version):
    """The CHANGELOG.md section(s) for this version, with ### headings turned into bold lines."""
    lines, inside = [], False
    with open("CHANGELOG.md", encoding="utf-8") as f:
        for line in f:
            if line.startswith("## ["):
                inside = line.startswith(f"## [{version}]")
                continue
            if inside:
                lines.append(re.sub(r"^###\s+(.*)", r"**\1**", line.rstrip()))
    return "\n".join(lines).strip()


def shorten(text, limit, more):
    if len(text) <= limit:
        return text
    cut = text[:limit - len(more) - 2]
    cut = cut[:cut.rfind("\n")] if "\n" in cut else cut
    return cut.rstrip() + "\n\n" + more


def game_range(games):
    return games[0] if len(games) == 1 else f"{games[0]} – {games[-1]}"


def build_message(cfg, project, version, found, repo, ping):
    first = found[0]
    url = f"{cfg['project_url']}/version/{first['id']}"
    release_url = f"https://github.com/{repo}/releases/tag/v{version}"
    changelog = changelog_section(version) or "Bug fixes and improvements."
    loaders = []
    for v in found.values():
        loaders += [LOADER_NAMES.get(l, l.title()) for l in v["loaders"] if l != "bukkit"]
    embed = {
        "author": {"name": "Vanish++ update", "url": cfg["project_url"], "icon_url": project["icon_url"]},
        "title": f"Vanish++ {version} is out",
        "url": url,
        "description": shorten(changelog, 3500, f"*…more in the [full changelog]({release_url}).*"),
        "color": COLOR,
        "thumbnail": {"url": project["icon_url"]},
        "fields": [
            {"name": "Minecraft", "value": game_range(first["game_versions"]), "inline": True},
            {"name": "Platforms", "value": " · ".join(dict.fromkeys(loaders)), "inline": True},
            {"name": "Channel", "value": first["version_type"].title(), "inline": True},
        ],
        "footer": {"text": "Released on Modrinth"},
        "timestamp": first["date_published"],
    }
    buttons = [{"type": 2, "style": 5, "label": f"Download {version}", "url": url, "emoji": {"name": "⬇️"}}]
    for i, v in found.items():
        if i:
            buttons.append({"type": 2, "style": 5, "label": v["name"].replace(f"Vanish++ {version} ", "").strip("()") or v["name"],
                            "url": f"{cfg['project_url']}/version/{v['id']}"})
    buttons += [
        {"type": 2, "style": 5, "label": "Changelog", "url": release_url, "emoji": {"name": "📜"}},
        {"type": 2, "style": 5, "label": "Wiki", "url": f"https://github.com/{repo}/wiki", "emoji": {"name": "📖"}},
    ]
    # username: the webhook itself is still called "Spidey Bot" on Discord
    message = {"username": "Versioner", "embeds": [embed], "components": [{"type": 1, "components": buttons[:5]}]}
    if ping == "@everyone":
        message["content"] = "@everyone"
        message["allowed_mentions"] = {"parse": ["everyone"]}
    elif ping.isdigit():
        message["content"] = f"<@&{ping}>"
        message["allowed_mentions"] = {"parse": [], "roles": [ping]}
    else:
        message["allowed_mentions"] = {"parse": []}
    return message


def check_limits(message):
    """Discord's documented embed limits - fail here instead of getting a 400 from the live channel."""
    e = message["embeds"][0]
    problems = []
    if len(e["title"]) > 256: problems.append("title > 256")
    if len(e["description"]) > 4096: problems.append("description > 4096")
    if len(e["author"]["name"]) > 256: problems.append("author > 256")
    for f in e["fields"]:
        if not f["value"] or len(f["value"]) > 1024 or len(f["name"]) > 256: problems.append(f"field {f['name']}")
    total = len(e["title"]) + len(e["description"]) + len(e["author"]["name"]) + len(e["footer"]["text"]) \
        + sum(len(f["name"]) + len(f["value"]) for f in e["fields"])
    if total > 6000: problems.append("embed total > 6000")
    for b in message["components"][0]["components"]:
        if len(b["label"]) > 80 or len(b["url"]) > 512: problems.append(f"button {b['label']}")
    if problems:
        sys.exit("::error::Message breaks Discord limits, nothing was posted: " + ", ".join(problems))


def send(webhook, message):
    """Posts the message. If Discord rejects the buttons, retries once with the links in the embed instead."""
    def post(msg, components):
        q = "?wait=true" + ("&with_components=true" if components else "")
        return request(webhook + q, data=json.dumps(msg).encode(), method="POST",
                       headers={"Content-Type": "application/json"})
    try:
        status, body = post(message, True)
    except urllib.error.HTTPError as e:
        if e.code != 400:
            raise
        print(f"::warning::Discord rejected the buttons ({e.read().decode()[:300]}), sending links in the embed instead")
        links = " · ".join(f"[{b['label']}]({b['url']})" for b in message["components"][0]["components"])
        fallback = {k: v for k, v in message.items() if k != "components"}
        fallback["embeds"] = [dict(message["embeds"][0], fields=message["embeds"][0]["fields"] + [{"name": "Links", "value": links}])]
        status, body = post(fallback, False)
    print(f"Posted to Discord (HTTP {status}, message {json.loads(body).get('id')})")


def main():
    version = os.environ["VERSION"].removeprefix("v")
    if "-" in version:
        print(f"{version} is a pre-release, not announcing it")
        return
    with open(".github/modrinth.json", encoding="utf-8") as f:
        cfg = json.load(f)
    found = wait_for_modrinth(cfg, version, int(os.environ.get("WAIT_MINUTES", "15")))
    print("Verified on Modrinth: " + ", ".join(f"{v['name']} ({v['id']})" for v in found.values()))
    project = get_json(f"{API}/project/{cfg['project_id']}")
    message = build_message(cfg, project, version, found, os.environ.get("REPO", "TheCommandCraft/Vanishpp"),
                            os.environ.get("PING", "@everyone").strip())
    check_limits(message)
    if os.environ.get("DRY_RUN") == "1":
        print(json.dumps(message, indent=2, ensure_ascii=False))
        return
    webhook = os.environ.get("DISCORD_WEBHOOK", "")
    if not webhook.startswith("https://discord.com/api/webhooks/"):
        sys.exit("::error::DISCORD_WEBHOOK is not set")
    send(webhook, message)


if __name__ == "__main__":
    main()
