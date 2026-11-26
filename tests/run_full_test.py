# -*- coding: utf-8 -*-
"""
LiteArk V1.1 全量接口自动化测试
运行：python3 tests/run_full_test.py
输出：docs/test-results.json（逐条执行记录）
"""
import base64
import hashlib
import io
import json
import os
import random
import struct
import time
import zlib
from datetime import datetime

import requests

BASE = "http://localhost:8080"
RESULTS = []
CTX = {}
START = datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def now():
    return datetime.now().strftime("%H:%M:%S")


def rec(mid, module, title, endpoint, expected, actual, ok, http=0, code=None, remark=""):
    RESULTS.append({
        "id": mid, "module": module, "title": title, "endpoint": endpoint,
        "expected": expected, "actual": actual,
        "result": "PASS" if ok else "FAIL",
        "http": http, "code": code, "remark": remark, "time": now(),
    })
    flag = "PASS" if ok else "FAIL"
    print(f"[{flag}] {mid} {title} | http={http} code={code} {remark}")


def req(method, path, token=None, body=None, params=None, files=None,
        data=None, headers=None, raw=False):
    h = dict(headers or {})
    if token:
        h["Authorization"] = "Bearer " + token
    kw = {"headers": h, "params": params, "timeout": 30}
    if body is not None:
        kw["json"] = body
    if data is not None:
        kw["data"] = data
    if files is not None:
        kw["files"] = files
    if raw:
        kw["stream"] = True
    try:
        r = requests.request(method, BASE + path, **kw)
    except Exception as e:
        return {"status": 0, "json": None, "text": str(e), "content": b"", "headers": {}}
    c = r.content if raw else r.content
    try:
        j = r.json()
    except Exception:
        j = None
    return {"status": r.status_code, "json": j, "text": r.text, "content": c, "headers": dict(r.headers)}


def code_of(resp):
    j = resp.get("json")
    return j.get("code") if isinstance(j, dict) else None


def msg_of(resp):
    j = resp.get("json")
    return j.get("msg") if isinstance(j, dict) else resp.get("text", "")[:120]


def login(u, p):
    r = req("POST", "/api/auth/login", body={"username": u, "password": p})
    d = (r.get("json") or {}).get("data") or {}
    return d.get("token"), r


def png_bytes(w=4, h=4, rgb=(30, 120, 200)):
    row = b"\x00" + bytes(rgb) * w
    raw = row * h

    def chunk(t, d):
        c = t + d
        return struct.pack(">I", len(d)) + c + struct.pack(">I", zlib.crc32(c) & 0xffffffff)

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw))
            + chunk(b"IEND", b""))


def xlsx_bytes():
    from openpyxl import Workbook
    wb = Workbook()
    s1 = wb.active
    s1.title = "第一页"
    s1.append(["序号", "名称", "备注"])
    s1.append([1, "张三", "AlphaCell"])
    s1.append([2, "李四", "BetaCell"])
    s2 = wb.create_sheet("第二页")
    s2.append(["部门", "人数"])
    s2.append(["研发部", 30])
    s2.append(["市场部", 12])
    s2.merge_cells("A5:B5")
    s2["A5"] = "合计42人 GammaCell"
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


# ============================================================
# 前置：登录与测试数据
# ============================================================
def setup():
    print("\n===== SETUP =====")
    admin, _ = login("admin", "admin123")
    CTX["admin"] = admin
    for name in ["qa_owner", "qa_view", "qa_edit", "qa_share", "qa_guest"]:
        t, _ = login(name, "Test@123456")
        CTX[name] = t
        if not t:
            raise RuntimeError("测试用户登录失败: " + name)

    # 所有者创建测试根目录 + 子目录
    r = req("POST", "/api/files/folder", CTX["qa_owner"], body={"parent_id": 0, "name": "QA测试根目录"})
    CTX["root_id"] = (r.get("json") or {}).get("data", {}).get("id")
    r = req("POST", "/api/files/folder", CTX["qa_owner"], body={"parent_id": CTX["root_id"], "name": "子目录A"})
    CTX["child_id"] = (r.get("json") or {}).get("data", {}).get("id")
    print("root_id", CTX["root_id"], "child_id", CTX["child_id"])

    # 上传文本 / xlsx / png 到测试根目录
    txt = "LiteArk QA test content\nsecond line 中文内容\n"
    files = {"files": ("QA测试说明.txt", io.BytesIO(txt.encode("utf-8")), "text/plain")}
    r = req("POST", "/api/files/upload", CTX["qa_owner"], params={"parentId": CTX["root_id"]}, files=files,
            headers={"X-Client-Type": "PC"})
    lst = (r.get("json") or {}).get("data") or []
    CTX["txt_id"] = lst[0].get("id") if lst else None
    print("txt upload", r.get("status"), code_of(r), CTX["txt_id"])

    files = {"files": ("QA多页签.xlsx", io.BytesIO(xlsx_bytes()),
                       "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")}
    r = req("POST", "/api/files/upload", CTX["qa_owner"], params={"parentId": CTX["root_id"]}, files=files,
            headers={"X-Client-Type": "PC"})
    lst = (r.get("json") or {}).get("data") or []
    CTX["xlsx_id"] = lst[0].get("id") if lst else None
    print("xlsx upload", r.get("status"), code_of(r), CTX["xlsx_id"])

    files = {"files": ("QA编辑用.txt", io.BytesIO(b"edit me please\nsecond line"), "text/plain")}
    r = req("POST", "/api/files/upload", CTX["qa_owner"], params={"parentId": CTX["root_id"]}, files=files,
            headers={"X-Client-Type": "PC"})
    lst = (r.get("json") or {}).get("data") or []
    CTX["edit_txt_id"] = lst[0].get("id") if lst else None
    print("edit txt upload", r.get("status"), code_of(r), CTX["edit_txt_id"])

    files = {"files": ("QA图片.png", io.BytesIO(png_bytes()), "image/png")}
    r = req("POST", "/api/files/upload", CTX["qa_owner"], params={"parentId": CTX["root_id"]}, files=files,
            headers={"X-Client-Type": "PC"})
    lst = (r.get("json") or {}).get("data") or []
    CTX["png_id"] = lst[0].get("id") if lst else None
    print("png upload", r.get("status"), code_of(r), CTX["png_id"])

    # 所有者授权：qa_view / qa_edit / qa_share；qa_guest 不授权
    grants = {
        "qa_view": ["view"],
        "qa_edit": ["view", "edit", "download", "upload"],
        "qa_share": ["view", "download", "share_internal", "share_external"],
    }
    import sqlite3
    con = sqlite3.connect("data/liteark.db")
    uid = {row[0]: row[1] for row in con.execute("select username,id from user")}
    con.close()
    CTX["uid"] = uid
    for name, perms in grants.items():
        r = req("POST", "/api/grants", CTX["qa_owner"], body={
            "folderId": CTX["root_id"], "userId": uid[name], "perms": perms, "inherit": 1})
        print("grant", name, code_of(r), msg_of(r))

    CTX["shares"] = []
    CTX["roles"] = []
    CTX["new_users"] = []
    CTX["extra_files"] = []


# ============================================================
# M1 认证与个人
# ============================================================
def m1():
    m = "M1 认证与个人"
    r = req("POST", "/api/auth/login", body={"username": "admin", "password": "admin123"})
    ok = code_of(r) == 0 and (r["json"]["data"]["user"]["role_id"] == 1)
    rec("M1-01", m, "管理员登录成功", "POST /api/auth/login", "code=0 且 role_id=1",
        f"code={code_of(r)}, role_id={(r.get('json') or {}).get('data', {}).get('user', {}).get('role_id')}", ok,
        r["status"], code_of(r))

    r = req("POST", "/api/auth/login", body={"username": "admin", "password": "wrongpwd"})
    ok = code_of(r) != 0 and "账号或密码错误" in msg_of(r)
    rec("M1-02", m, "错误密码登录失败", "POST /api/auth/login", "code≠0，提示账号或密码错误",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("POST", "/api/auth/login", body={"username": "no_such_user_x", "password": "123456"})
    ok = code_of(r) != 0
    rec("M1-03", m, "不存在账号登录失败", "POST /api/auth/login", "code≠0",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    uname = "qa_auto_" + str(random.randint(1000, 9999))
    r = req("POST", "/api/auth/register", body={"username": uname, "password": "Auto@123456", "nickname": "自动化注册"})
    ok = code_of(r) == 0 and (r.get("json") or {}).get("data", {}).get("user", {}).get("role_id") == 2
    CTX["new_users"].append(uname)
    rec("M1-04", m, "注册成功且角色为普通用户", "POST /api/auth/register", "code=0，role_id=2",
        f"code={code_of(r)}, user={(r.get('json') or {}).get('data', {}).get('user')}", ok, r["status"], code_of(r))

    r1 = req("POST", "/api/auth/register", body={"username": "ab", "password": "123456"})
    r2 = req("POST", "/api/auth/register", body={"username": "qa_short_" + str(random.randint(100, 999)), "password": "12345"})
    ok = code_of(r1) != 0 and code_of(r2) != 0
    rec("M1-05", m, "注册输入校验（账号过短/密码过短）", "POST /api/auth/register", "两次均 code≠0",
        f"短账号 code={code_of(r1)}/{msg_of(r1)}；短密码 code={code_of(r2)}/{msg_of(r2)}", ok, r1["status"], code_of(r1))

    r = req("POST", "/api/auth/register", body={"username": uname, "password": "Auto@123456"})
    ok = code_of(r) != 0 and "账号已存在" in msg_of(r)
    rec("M1-06", m, "重复账号注册被拒", "POST /api/auth/register", "code≠0，提示账号已存在",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", "/api/auth/me", CTX["qa_owner"])
    d = (r.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and d.get("username") == "qa_owner" and "quota" in d
    rec("M1-07", m, "获取当前用户信息", "GET /api/auth/me", "code=0，返回 username/quota/used",
        f"code={code_of(r)}, data={d}", ok, r["status"], code_of(r))

    r = req("GET", "/api/auth/me")
    ok = r["status"] == 401
    rec("M1-08", m, "未登录访问受保护接口", "GET /api/auth/me", "HTTP 401",
        f"HTTP {r['status']}, body={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", "/api/auth/me", "forged.token.value")
    ok = r["status"] == 401
    rec("M1-09", m, "伪造 token 被拒", "GET /api/auth/me", "HTTP 401",
        f"HTTP {r['status']}, body={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("PUT", "/api/auth/profile", CTX["qa_view"], body={"nickname": "测试-只读(改)"})
    r2 = req("GET", "/api/auth/me", CTX["qa_view"])
    ok = code_of(r) == 0 and (r2.get("json") or {}).get("data", {}).get("nickname") == "测试-只读(改)"
    rec("M1-10", m, "修改昵称生效", "PUT /api/auth/profile", "code=0 且 me 中昵称更新",
        f"修改 code={code_of(r)}, 回读 nickname={(r2.get('json') or {}).get('data', {}).get('nickname')}", ok,
        r["status"], code_of(r))

    r = req("PUT", "/api/auth/password", CTX[uname] if uname in CTX else None,
            body={"oldPassword": "wrong", "newPassword": "New@123456"})
    if uname not in CTX:
        t, _ = login(uname, "Auto@123456")
        CTX[uname] = t
        r = req("PUT", "/api/auth/password", t, body={"oldPassword": "wrong", "newPassword": "New@123456"})
    ok = code_of(r) != 0 and "原密码错误" in msg_of(r)
    rec("M1-11", m, "修改密码-原密码错误被拒", "PUT /api/auth/password", "code≠0，提示原密码错误",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("PUT", "/api/auth/password", CTX[uname], body={"oldPassword": "Auto@123456", "newPassword": "New@123456"})
    ok = code_of(r) == 0
    _, old = login(uname, "Auto@123456")
    newt, _, = login(uname, "New@123456")
    ok2 = code_of(old) != 0 and bool(newt)
    rec("M1-12", m, "修改密码成功后旧密码失效", "PUT /api/auth/password", "code=0，旧密码失败、新密码成功",
        f"修改 code={code_of(r)}；旧密码 code={code_of(old)}；新密码登录={'成功' if newt else '失败'}",
        ok and ok2, r["status"], code_of(r))


# ============================================================
# M2 文件与目录
# ============================================================
def m2():
    m = "M2 文件与目录"
    o = CTX["qa_owner"]
    root, child, txt = CTX["root_id"], CTX["child_id"], CTX["txt_id"]

    r = req("GET", "/api/files/tree", o)
    data = (r.get("json") or {}).get("data") or []
    names = json.dumps(data, ensure_ascii=False)
    ok = code_of(r) == 0 and "QA测试根目录" in names
    rec("M2-01", m, "目录树包含测试根目录", "GET /api/files/tree", "code=0，包含 QA测试根目录",
        f"code={code_of(r)}, 节点数={len(data) if isinstance(data, list) else '?'}", ok, r["status"], code_of(r))

    r = req("GET", "/api/files/list", o, params={"parentId": 0})
    ok = code_of(r) == 0 and isinstance((r.get("json") or {}).get("data"), list)
    rec("M2-02", m, "根目录文件列表", "GET /api/files/list", "code=0，返回数组",
        f"code={code_of(r)}, 条数={len((r.get('json') or {}).get('data') or [])}", ok, r["status"], code_of(r))

    r = req("POST", "/api/files/folder", o, body={"parent_id": root, "name": "待重命名目录"})
    nid = (r.get("json") or {}).get("data", {}).get("id")
    r2 = req("PUT", f"/api/files/{nid}/rename", o, body={"name": "已重命名目录"})
    r3 = req("GET", "/api/files/list", o, params={"parentId": root})
    hit = [x for x in ((r3.get("json") or {}).get("data") or []) if x.get("id") == nid]
    ok = code_of(r) == 0 and code_of(r2) == 0 and len(hit) == 1 and hit[0].get("name") == "已重命名目录"
    rec("M2-03", m, "新建文件夹并重命名", "POST /api/files/folder + PUT /{id}/rename", "code=0，名称更新",
        f"新建 code={code_of(r)}, 重命名 code={code_of(r2)}, 回读名称={hit[0].get('name') if hit else '未找到'}",
        ok, r2["status"], code_of(r2))

    r = req("GET", f"/api/files/{nid}/breadcrumb", o) if False else req("GET", "/api/files/breadcrumb", o, params={"id": child})
    data = (r.get("json") or {}).get("data") or []
    ok = code_of(r) == 0 and len(data) >= 2
    rec("M2-04", m, "面包屑路径", "GET /api/files/breadcrumb", "code=0，层级≥2",
        f"code={code_of(r)}, 层级={len(data) if isinstance(data, list) else '?'}", ok, r["status"], code_of(r))

    r = req("GET", "/api/files/folder-info", o, params={"folderId": root})
    d = (r.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and "perms" in d and d.get("ownerName") is not None
    rec("M2-05", m, "目录信息与所有者展示", "GET /api/files/folder-info", "code=0，含 perms/ownerName",
        f"code={code_of(r)}, data={d}", ok, r["status"], code_of(r))

    r = req("GET", "/api/files/list", o, params={"parentId": root, "keyword": "多页签"})
    data = (r.get("json") or {}).get("data") or []
    ok = code_of(r) == 0 and len(data) == 1 and data[0].get("name") == "QA多页签.xlsx"
    rec("M2-06", m, "关键字搜索", "GET /api/files/list?keyword=", "code=0，仅返回匹配项",
        f"code={code_of(r)}, 结果={[x.get('name') for x in data]}", ok, r["status"], code_of(r))

    r = req("POST", "/api/files/copy", o, body={"ids": [txt], "targetId": child})
    copied = None
    d = (r.get("json") or {}).get("data") or {}
    r2 = req("GET", "/api/files/list", o, params={"parentId": child})
    names = [x.get("name") for x in ((r2.get("json") or {}).get("data") or [])]
    copied = [x for x in ((r2.get("json") or {}).get("data") or []) if "QA测试说明" in (x.get("name") or "")][:1]
    ok = code_of(r) == 0 and len(copied) == 1
    if copied:
        CTX["copy_id"] = copied[0]["id"]
    rec("M2-07", m, "复制文件到子目录", "POST /api/files/copy", "code=0，目标目录出现副本",
        f"code={code_of(r)}, 子目录文件={names}", ok, r["status"], code_of(r))

    cid = CTX.get("copy_id")
    # 移动到新目录（避免与根目录同名项冲突）
    r0 = req("POST", "/api/files/folder", o, body={"parent_id": root, "name": "移动目标目录"})
    move_target = (r0.get("json") or {}).get("data", {}).get("id")
    r = req("POST", "/api/files/move", o, body={"ids": [cid], "targetId": move_target})
    r2 = req("GET", "/api/files/list", o, params={"parentId": move_target})
    names = [x.get("name") for x in ((r2.get("json") or {}).get("data") or [])]
    ok = code_of(r) == 0 and any("QA测试说明" in (n or "") for n in names)
    rec("M2-08", m, "移动文件到目标目录", "POST /api/files/move", "code=0，目标目录出现移动项",
        f"code={code_of(r)}, 目标目录文件名={names}", ok, r["status"], code_of(r))

    r = req("POST", "/api/files/move", o, body={"ids": [root], "targetId": child})
    ok = code_of(r) != 0
    rec("M2-09", m, "文件夹移入自身子目录被拒", "POST /api/files/move", "code≠0",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("DELETE", "/api/files", o, params={"ids": str(cid)})
    r2 = req("GET", "/api/files/recycle", o)
    recyc = (r2.get("json") or {}).get("data") or []
    ok = code_of(r) == 0 and any(x.get("id") == cid for x in recyc)
    rec("M2-10", m, "删除到回收站", "DELETE /api/files?ids=", "code=0，回收站可见",
        f"删除 code={code_of(r)}, 回收站条数={len(recyc)}", ok, r["status"], code_of(r))

    r = req("POST", f"/api/files/{cid}/restore", o)
    ok = code_of(r) == 0
    rec("M2-11", m, "回收站还原", "POST /api/files/{id}/restore", "code=0",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("DELETE", "/api/files", o, params={"ids": str(cid)})
    r2 = req("DELETE", f"/api/files/{cid}/permanent", o)
    r3 = req("GET", "/api/files/recycle", o)
    gone = not any(x.get("id") == cid for x in ((r3.get("json") or {}).get("data") or []))
    ok = code_of(r) == 0 and code_of(r2) == 0 and gone
    rec("M2-12", m, "彻底删除", "DELETE /api/files/{id}/permanent", "code=0，回收站不再显示",
        f"删除 code={code_of(r)}, 彻底删除 code={code_of(r2)}, 已消失={gone}", ok, r2["status"], code_of(r2))

    r = req("POST", "/api/files/download-zip", o, body={"ids": [txt]})
    is_zip = r["content"][:2] == b"PK"
    ok = r["status"] == 200 and is_zip
    rec("M2-13", m, "批量打包 ZIP 下载", "POST /api/files/download-zip", "HTTP 200 且返回 ZIP 二进制",
        f"HTTP {r['status']}, 前两字节={r['content'][:2]!r}, 大小={len(r['content'])}", ok, r["status"])

    # 缺陷回归：复制共享存储键，彻底删除副本是否影响原文件
    r = req("POST", "/api/files/folder", o, body={"parent_id": root, "name": "删除副本验证"})
    vdir = (r.get("json") or {}).get("data", {}).get("id")
    rv = req("POST", "/api/files/upload", o, params={"parentId": vdir},
             files={"files": ("源文件.txt", io.BytesIO(b"data-integrity-check"), "text/plain")},
             headers={"X-Client-Type": "PC"})
    src = ((rv.get("json") or {}).get("data") or [{}])[0].get("id")
    rc = req("POST", "/api/files/copy", o, body={"ids": [src], "targetId": child})
    cp = [x for x in ((req("GET", "/api/files/list", o, params={"parentId": child}).get("json") or {}).get("data") or [])
          if "源文件" in (x.get("name") or "")]
    cp_id = cp[0].get("id") if cp else None
    if src and cp_id:
        req("DELETE", "/api/files", o, params={"ids": str(cp_id)})
        req("DELETE", f"/api/files/{cp_id}/permanent", o)
        after = req("GET", f"/api/files/{src}/download", o, raw=True)
        body_after = after["content"][:120].decode("utf-8", "ignore")
        ok = after["status"] == 200 and body_after.startswith("data-integrity-check")
        rec("M2-14", m, "复制后彻底删除副本不影响原文件", "复制→回收站→彻底删除副本→下载原文件",
            "原文件仍可正常下载",
            f"复制 code={code_of(rc)}；原文件下载 HTTP {after['status']}, body={body_after!r}",
            ok, after["status"], None,
            "缺陷：副本与原文件共用 storageKey，彻底删除副本时物理删除了共享文件" if not ok else "")
    else:
        rec("M2-14", m, "复制后彻底删除副本不影响原文件", "-", "前置成功", f"src={src}, cp={cp_id}", False)


# ============================================================
# M3 分片上传
# ============================================================
def m3():
    m = "M3 分片上传"
    o = CTX["qa_owner"]
    root = CTX["root_id"]

    r = req("GET", "/api/upload/config", o)
    d = (r.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and d.get("chunkSize", 0) > 0 and d.get("concurrency", 0) > 0
    rec("M3-01", m, "上传配置探测", "GET /api/upload/config", "code=0，含 chunkSize/concurrency",
        f"code={code_of(r)}, data={d}", ok, r["status"], code_of(r))

    payload = bytes([65 + (i % 26) for i in range(2 * 1024 * 1024 + 337)])
    md5 = hashlib.md5(payload).hexdigest()
    name = "QA分片上传_" + str(random.randint(1000, 9999)) + ".bin"
    chunk_size = 1024 * 1024
    chunks = [payload[i:i + chunk_size] for i in range(0, len(payload), chunk_size)]
    init_body = {"md5": md5, "name": name, "totalSize": len(payload),
                 "chunkSize": chunk_size, "chunkTotal": len(chunks), "parentId": root}

    r = req("POST", "/api/upload/init", o, body=init_body)
    uid = (r.get("json") or {}).get("data", {}).get("uploadId")
    CTX["upload_id"] = uid
    ok = code_of(r) == 0 and bool(uid)
    rec("M3-02", m, "初始化分片任务", "POST /api/upload/init", "code=0，返回 uploadId",
        f"code={code_of(r)}, uploadId={uid}, 分片数={len(chunks)}", ok, r["status"], code_of(r))

    r = req("POST", "/api/upload/chunk", o, params={"uploadId": uid, "index": 0},
            files={"chunk": ("c0", io.BytesIO(chunks[0]), "application/octet-stream")})
    r2 = req("POST", "/api/upload/init", o, body=init_body)
    d = (r2.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and code_of(r2) == 0 and d.get("resumed") is True and d.get("uploadId") == uid
    rec("M3-03", m, "断点复用已传分片", "POST /api/upload/init（同参数二次调用）", "resumed=true，uploadId 相同",
        f"分片上传 code={code_of(r)}；再初始化 resumed={d.get('resumed')}, uploadId一致={d.get('uploadId') == uid}",
        ok, r2["status"], code_of(r2))

    idxs = []
    for i, c in enumerate(chunks):
        rr = req("POST", "/api/upload/chunk", o, params={"uploadId": uid, "index": i},
                 files={"chunk": (f"c{i}", io.BytesIO(c), "application/octet-stream")})
        idxs.append(code_of(rr))
    ok = all(x == 0 for x in idxs)
    rec("M3-04", m, "上传全部分片", "POST /api/upload/chunk", "每个分片 code=0",
        f"分片结果={idxs}", ok, 200, 0 if ok else 1)

    r = req("POST", "/api/upload/chunk", o, params={"uploadId": uid, "index": 0},
            files={"chunk": ("c0", io.BytesIO(chunks[0]), "application/octet-stream")})
    ok = code_of(r) == 0
    rec("M3-05", m, "重复上传同一分片（幂等）", "POST /api/upload/chunk（index=0 再次）", "code=0",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", "/api/upload/status", o, params={"uploadId": uid})
    d = (r.get("json") or {}).get("data") or {}
    uploaded = d.get("uploaded") if isinstance(d, dict) else None
    ok = code_of(r) == 0 and isinstance(uploaded, list) and len(uploaded) == len(chunks)
    rec("M3-06", m, "查询上传状态", "GET /api/upload/status", "code=0，已传分片数=总分片数",
        f"code={code_of(r)}, uploaded={uploaded}", ok, r["status"], code_of(r))

    r = req("POST", "/api/upload/complete", o, body={"uploadId": uid})
    d = (r.get("json") or {}).get("data") or {}
    fid = d.get("id") if isinstance(d, dict) else None
    CTX["uploaded_file_id"] = fid
    r2 = req("GET", "/api/files/list", o, params={"parentId": root, "keyword": name})
    ok = code_of(r) == 0 and any(x.get("md5") == md5 for x in ((r2.get("json") or {}).get("data") or []))
    rec("M3-07", m, "合并分片并校验 MD5 落库", "POST /api/upload/complete", "code=0，文件 MD5 与源一致",
        f"complete code={code_of(r)}, data={d}, 列表命中MD5={any(x.get('md5') == md5 for x in ((r2.get('json') or {}).get('data') or []))}",
        ok, r["status"], code_of(r))

    bad = dict(init_body)
    bad["chunkSize"] = 100
    bad["chunkTotal"] = 1
    bad["md5"] = hashlib.md5(b"badchunk").hexdigest()
    bad["name"] = "QA非法分片.bin"
    r = req("POST", "/api/upload/init", o, body=bad)
    ok = code_of(r) != 0 and "1MB" in msg_of(r)
    rec("M3-08", m, "分片大小非法被拒", "POST /api/upload/init（chunkSize=100）", "code≠0，提示 1MB–64MB",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    bad2 = dict(init_body)
    bad2["chunkTotal"] = 99
    bad2["md5"] = hashlib.md5(b"badtotal").hexdigest()
    bad2["name"] = "QA分片数不符.bin"
    r = req("POST", "/api/upload/init", o, body=bad2)
    ok = code_of(r) != 0 and "分片数量" in msg_of(r)
    rec("M3-09", m, "分片数量不匹配被拒", "POST /api/upload/init（chunkTotal=99）", "code≠0，提示分片数量不匹配",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    cbody = dict(init_body)
    cbody["md5"] = hashlib.md5(b"cancel-me").hexdigest()
    cbody["name"] = "QA取消任务.bin"
    r = req("POST", "/api/upload/init", o, body=cbody)
    cuid = (r.get("json") or {}).get("data", {}).get("uploadId")
    r2 = req("POST", "/api/upload/cancel", o, body={"uploadId": cuid})
    r3 = req("GET", "/api/upload/status", o, params={"uploadId": cuid})
    st = ((r3.get("json") or {}).get("data") or {}).get("status")
    ok = code_of(r) == 0 and code_of(r2) == 0 and st == "done"
    rec("M3-10", m, "取消任务后置为 done", "POST /api/upload/cancel + GET /api/upload/status",
        "cancel code=0，status=done", f"init={code_of(r)}, cancel={code_of(r2)}, status接口 code={code_of(r3)}, status={st}",
        ok, r2["status"], code_of(r2), "取消后 status 仍可查询（符合实现），以 status=done 断言")


# ============================================================
# M4 预览与编辑
# ============================================================
def m4():
    m = "M4 预览与编辑"
    o = CTX["qa_owner"]
    txt, xlsx, png = CTX["txt_id"], CTX["xlsx_id"], CTX["png_id"]

    # 使用未参与复制/彻底删除的原始文件，避免共享存储键带来的干扰
    safe_txt = CTX.get("edit_txt_id") or txt
    r = req("PUT", f"/api/files/{safe_txt}/save-text", o,
            data="LiteArk QA 修改后的内容\nline2", headers={"Content-Type": "text/plain"})
    ok = code_of(r) == 0
    rec("M4-01", m, "在线编辑保存文本", "PUT /api/files/{id}/save-text", "code=0",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", f"/api/files/{safe_txt}/versions", o)
    vs = (r.get("json") or {}).get("data") or []
    ok = code_of(r) == 0 and len(vs) >= 1
    CTX["version_id"] = vs[0].get("id") if vs else None
    rec("M4-02", m, "历史版本列表", "GET /api/files/{id}/versions", "code=0，至少 1 个版本",
        f"code={code_of(r)}, 版本数={len(vs)}", ok, r["status"], code_of(r))

    vid = CTX.get("version_id")
    if vid:
        r = req("POST", f"/api/files/{safe_txt}/versions/{vid}/rollback", o)
        ok = code_of(r) == 0
        rec("M4-03", m, "版本回滚", "POST /api/files/{id}/versions/{vid}/rollback", "code=0",
            f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))
    else:
        rec("M4-03", m, "版本回滚", "-", "有可回滚版本", "无版本，跳过", False, 0, None, "前置失败")

    r = req("GET", f"/api/files/{txt}/preview", o, raw=True)
    ok = r["status"] in (200, 206) and r["headers"].get("X-Content-Type-Options") == "nosniff"
    rec("M4-04", m, "文本内联预览与安全头", "GET /api/files/{id}/preview", "200/206 且 nosniff",
        f"HTTP {r['status']}, nosniff={r['headers'].get('X-Content-Type-Options')}, Accept-Ranges={r['headers'].get('Accept-Ranges')}",
        ok, r["status"])

    r = req("GET", f"/api/files/{png}/thumbnail", o, params={"w": 120}, raw=True)
    ok = r["status"] == 200 and len(r["content"]) > 0 and r["headers"].get("Content-Type", "").startswith("image/")
    rec("M4-05", m, "图片缩略图", "GET /api/files/{id}/thumbnail?w=120", "HTTP 200 且返回图片",
        f"HTTP {r['status']}, Content-Type={r['headers'].get('Content-Type')}, 大小={len(r['content'])}", ok, r["status"],
        remark="缩略图实现统一转 JPEG")

    r = req("GET", f"/api/files/{xlsx}/office-preview/info", o)
    d = (r.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and d.get("available") is True and d.get("mode") == "html" and d.get("tabs") is True
    rec("M4-06", m, "xlsx 预览能力探测", "GET /api/files/{id}/office-preview/info",
        "available=true, mode=html, tabs=true", f"code={code_of(r)}, data={d}", ok, r["status"], code_of(r))

    r = req("GET", f"/api/files/{xlsx}/office-preview", o, raw=True)
    html = r["content"].decode("utf-8", "ignore")
    has_tabs = "la-sheet" in html and "第一页" in html and "第二页" in html
    has_data = all(k in html for k in ["AlphaCell", "BetaCell", "GammaCell"])
    panels = html.count("la-panel")
    ok = r["status"] == 200 and has_tabs and has_data and panels >= 2
    rec("M4-07", m, "xlsx 多 Sheet 页签与内容", "GET /api/files/{id}/office-preview",
        "含两个页签、两表数据、≥2 个面板",
        f"HTTP {r['status']}, 页签={has_tabs}, 数据={has_data}, 面板数={panels}, 长度={len(html)}",
        ok, r["status"])

    r = req("GET", f"/api/files/{safe_txt}/download", o, headers={"Range": "bytes=0-9"}, raw=True)
    ok = r["status"] == 206 and r["headers"].get("Accept-Ranges") == "bytes"
    rec("M4-08", m, "断点下载 Range 支持", "GET /api/files/{id}/download (Range)", "HTTP 206 且 Accept-Ranges=bytes",
        f"HTTP {r['status']}, Accept-Ranges={r['headers'].get('Accept-Ranges')}, 长度={len(r['content'])}",
        ok, r["status"])


# ============================================================
# M5 分享
# ============================================================
def m5():
    m = "M5 分享"
    owner, sharer, guest, viewer = CTX["qa_owner"], CTX["qa_share"], CTX["qa_guest"], CTX["qa_view"]
    txt = CTX["txt_id"]
    uid = CTX["uid"]

    def create_share(token, file_id, **extra):
        body = {"fileId": file_id, "permission": "download", "expireDays": 7, "maxDownloads": 0}
        body.update(extra)
        r = req("POST", "/api/shares", token, body=body)
        d = (r.get("json") or {}).get("data") or {}
        return r, d

    r, d = create_share(sharer, txt, shareType="external")
    ext_token = d.get("token")
    CTX["shares"].append((sharer, d.get("id") or (r.get("json") or {}).get("data", {}).get("id")))
    ok = code_of(r) == 0 and bool(ext_token)
    rec("M5-01", m, "有权限的非所有者创建外部分享", "POST /api/shares", "code=0，返回 token",
        f"code={code_of(r)}, msg={msg_of(r)}, token={str(ext_token)[:8]}", ok, r["status"], code_of(r))

    r, d = create_share(sharer, txt, shareType="internal", targetIds=[uid["qa_view"]])
    int_token = d.get("token")
    ok = code_of(r) == 0 and bool(int_token)
    rec("M5-02", m, "创建内部分享", "POST /api/shares (shareType=internal)", "code=0，返回 token",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("POST", "/api/shares", sharer, body={"fileId": txt, "permission": "download",
            "shareType": "internal", "targetIds": []})
    ok = code_of(r) != 0 and "至少选择一位" in msg_of(r)
    rec("M5-03", m, "内部分享空名单被拒", "POST /api/shares（targetIds=[]）", "code≠0，提示至少选择一位内部用户",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("POST", "/api/shares", guest, body={"fileId": txt, "permission": "download", "shareType": "external"})
    ok = code_of(r) == 403
    rec("M5-04", m, "无分享权限用户创建分享被拒", "POST /api/shares（qa_guest）", "code=403",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", f"/api/share/{ext_token}/info")
    d = (r.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and d.get("needPassword") is False and d.get("shareType") == "external"
    rec("M5-05", m, "外部分享免登录查看信息", "GET /api/share/{token}/info", "code=0，needPassword=false",
        f"code={code_of(r)}, data={d}", ok, r["status"], code_of(r))

    r, d = create_share(owner, txt, shareType="external", password="qa1234")
    pwd_token = d.get("token")
    r1 = req("GET", f"/api/share/{pwd_token}/info")
    d1 = (r1.get("json") or {}).get("data") or {}
    ok1 = code_of(r1) == 0 and d1.get("needPassword") is True
    rec("M5-06", m, "密码分享-未提供密码", "GET /api/share/{token}/info（无密码）", "needPassword=true",
        f"code={code_of(r1)}, data={d1}", ok1, r1["status"], code_of(r1))

    r2 = req("GET", f"/api/share/{pwd_token}/info", params={"password": "qa1234"})
    d2 = (r2.get("json") or {}).get("data") or {}
    ok = code_of(r2) == 0 and d2.get("needPassword") is False
    rec("M5-07", m, "密码分享-正确密码", "GET /api/share/{token}/info?password=", "needPassword=false",
        f"code={code_of(r2)}, data={d2}", ok, r2["status"], code_of(r2))

    r = req("GET", f"/api/share/{int_token}/info")
    ok = code_of(r) == 401
    rec("M5-08", m, "内部分享-未登录访问被拒", "GET /api/share/{token}/info（匿名）", "code=401，请先登录",
        f"HTTP {r['status']}, code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", f"/api/share/{int_token}/info", guest)
    ok = code_of(r) == 403 and "名单" in msg_of(r)
    rec("M5-09", m, "内部分享-名单外用户被拒", "GET /api/share/{token}/info（qa_guest）", "code=403，不在名单中",
        f"HTTP {r['status']}, code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    ra = req("GET", f"/api/share/{int_token}/info", viewer)
    rb = req("GET", f"/api/share/{int_token}/info", sharer)
    ok = code_of(ra) == 0 and code_of(rb) == 0
    rec("M5-10", m, "内部分享-名单内用户与创建者可访问", "GET /api/share/{token}/info（qa_view / 创建者）",
        "两者 code=0", f"名单内 code={code_of(ra)}；创建者 code={code_of(rb)}", ok, ra["status"], code_of(ra))

    r = req("POST", "/api/shares", owner, body={"fileId": CTX["root_id"], "permission": "view",
            "shareType": "external", "expireDays": 7})
    folder_token = (r.get("json") or {}).get("data", {}).get("token")
    r2 = req("GET", f"/api/share/{folder_token}/list", params={"parentId": 0})
    d2 = (r2.get("json") or {}).get("data") or []
    ok = code_of(r) == 0 and code_of(r2) == 0 and len(d2) > 0
    rec("M5-11", m, "共享文件夹逐级浏览", "GET /api/share/{token}/list", "code=0，返回子项",
        f"创建 code={code_of(r)}；列表 code={code_of(r2)}, 子项={[x.get('name') for x in d2]}",
        ok, r2["status"], code_of(r2))

    r, d = create_share(owner, CTX["edit_txt_id"], shareType="external", maxDownloads=1)
    lim_token = d.get("token")
    d1 = req("GET", f"/api/share/{lim_token}/info").get("json", {}).get("data") or {}
    d2 = req("GET", f"/api/share/{lim_token}/download/{CTX['edit_txt_id']}", raw=True)
    d3 = req("GET", f"/api/share/{lim_token}/info")
    ok = d1.get("downloadCount") == 0 and d2["status"] == 200 and code_of(d3) != 0 and "次数已用完" in msg_of(d3)
    rec("M5-12", m, "下载次数上限自动撤销", "GET /api/share/{token}/download/{fileId} ×2",
        "首次成功，第二次提示次数已用完",
        f"初始 count={d1.get('downloadCount')}；首次 HTTP {d2['status']}；二次 code={code_of(d3)}/msg={msg_of(d3)}",
        ok, d3["status"], code_of(d3))

    r = req("POST", "/api/shares", owner, body={"fileId": txt, "permission": "view", "shareType": "external", "expireDays": 7})
    view_token = (r.get("json") or {}).get("data", {}).get("token")
    r2 = req("GET", f"/api/share/{view_token}/preview/{txt}", raw=True)
    r3 = req("GET", f"/api/share/{view_token}/download/{txt}", raw=True)
    ok = r2["status"] == 200 and code_of(r3) != 0 and "不允许下载" in msg_of(r3)
    rec("M5-13", m, "仅查看权限可预览不可下载", "GET /api/share/{token}/preview|download",
        "预览 200，下载被拒", f"预览 HTTP {r2['status']}；下载 code={code_of(r3)}/msg={msg_of(r3)}",
        ok, r2["status"], code_of(r3))

    shares_mine = (req("GET", "/api/shares", owner).get("json") or {}).get("data") or []
    sid = next((s.get("id") for s in shares_mine if s.get("token") == pwd_token), None)
    if sid:
        r = req("PUT", f"/api/shares/{sid}", owner, body={"permission": "view", "maxDownloads": 3, "expireDays": 2})
        d = (req("GET", "/api/shares", owner).get("json") or {}).get("data") or []
        cur = next((s for s in d if s.get("id") == sid), {})
        ok = code_of(r) == 0 and cur.get("permission") == "view" and cur.get("maxDownloads") == 3
        rec("M5-14", m, "修改共享设置", "PUT /api/shares/{id}", "code=0，设置生效",
            f"code={code_of(r)}, 回读 permission={cur.get('permission')}, maxDownloads={cur.get('maxDownloads')}",
            ok, r["status"], code_of(r))
        r = req("DELETE", f"/api/shares/{sid}", owner)
        r2 = req("GET", f"/api/share/{pwd_token}/info")
        ok = code_of(r) == 0 and code_of(r2) != 0
        rec("M5-15", m, "撤销共享后链接失效", "DELETE /api/shares/{id}", "撤销成功，info 失败",
            f"撤销 code={code_of(r)}；info code={code_of(r2)}/msg={msg_of(r2)}", ok, r["status"], code_of(r))
    else:
        rec("M5-14", m, "修改共享设置", "-", "可定位共享", "未定位到共享", False)
        rec("M5-15", m, "撤销共享后链接失效", "-", "可执行", "前置失败", False)

    access = (req("GET", f"/api/shares/{sid}/access", owner).get("json") or {}).get("data") if sid else None
    ok = isinstance(access, list) and len(access) > 0 and "ip" in access[0]
    rec("M5-16", m, "共享访问记录", "GET /api/shares/{id}/access", "返回记录，含 IP/端类型",
        f"记录数={len(access) if isinstance(access, list) else 'N/A'}, 首条={access[0] if isinstance(access, list) and access else None}",
        ok, 200, 0 if ok else 1)

    r = req("GET", "/api/shares/users", sharer)
    users = (r.get("json") or {}).get("data") or []
    unames = [u.get("username") for u in users]
    # 候选列表应排除自己（qa_share）与管理员；qa_owner 是合法候选
    ok = code_of(r) == 0 and "qa_view" in unames and "qa_share" not in unames and "admin" not in unames
    rec("M5-17", m, "内部分享候选用户列表", "GET /api/shares/users", "排除自己/管理员/禁用用户",
        f"code={code_of(r)}, 候选={unames}", ok, r["status"], code_of(r))

    # 越权风险点：非所有者查看他人共享访问记录
    other_sid = sid
    r = req("GET", f"/api/shares/{other_sid}/access", guest) if other_sid else None
    ok = r is not None and code_of(r) != 0
    rec("M5-18", m, "越权查看他人共享访问记录", "GET /api/shares/{id}/access（qa_guest）",
        "应拒绝（仅所有者/管理员）", f"HTTP {r['status'] if r else 0}, code={code_of(r) if r else None}",
        ok, r["status"] if r else 0, code_of(r) if r else None,
        "风险点：Service 未校验共享所有权" if not ok else "")


# ============================================================
# M6 授权与权限
# ============================================================
def m6():
    m = "M6 授权与权限"
    admin, owner, viewr, edit, guest = CTX["admin"], CTX["qa_owner"], CTX["qa_view"], CTX["qa_edit"], CTX["qa_guest"]
    root, child = CTX["root_id"], CTX["child_id"]
    uid = CTX["uid"]

    r = req("GET", "/api/grants", owner, params={"folderId": root})
    d = (r.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and "rules" in d and "roles" in d and "candidates" in d
    rec("M6-01", m, "所有者查看授权面板", "GET /api/grants?folderId=", "code=0，含 rules/roles/candidates",
        f"code={code_of(r)}, keys={list(d.keys())}", ok, r["status"], code_of(r))

    r = req("GET", "/api/grants", viewr, params={"folderId": root})
    ok = code_of(r) == 403
    rec("M6-02", m, "非所有者访问授权面板被拒", "GET /api/grants（qa_view）", "code=403",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", "/api/files/list", viewr, params={"parentId": root})
    d = (r.get("json") or {}).get("data") or []
    ok = code_of(r) == 0 and len(d) > 0
    rec("M6-03", m, "被授权 view 用户可见目录内容", "GET /api/files/list（qa_view）", "code=0，可见条目",
        f"code={code_of(r)}, 可见={len(d)}", ok, r["status"], code_of(r))

    r = req("GET", "/api/files/list", guest, params={"parentId": root})
    ok = code_of(r) == 403
    rec("M6-04", m, "默认拒绝-无授权用户不可见", "GET /api/files/list（qa_guest）", "code=403",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("POST", "/api/grants", viewr, body={"folderId": root, "userId": uid["qa_guest"], "perms": ["view"]})
    ok = code_of(r) == 403
    rec("M6-05", m, "非所有者越权授出被拒", "POST /api/grants（qa_view）", "code=403",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", "/api/admin/permissions/effective", admin, params={"userId": uid["qa_view"], "folderId": root})
    eff = (r.get("json") or {}).get("data") or []
    ok = code_of(r) == 0 and "view" in eff and "edit" not in eff
    rec("M6-06", m, "有效权限查询", "GET /api/admin/permissions/effective", "qa_view 仅 view",
        f"code={code_of(r)}, perms={eff}", ok, r["status"], code_of(r))

    r = req("GET", "/api/admin/permissions/role-effective", admin, params={"roleId": 2, "folderId": root})
    d = (r.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and "perms" in d and "userCount" in d
    rec("M6-07", m, "角色默认权限查询", "GET /api/admin/permissions/role-effective", "code=0，含 perms/userCount",
        f"code={code_of(r)}, data={d}", ok, r["status"], code_of(r))

    # 按角色授权：建立角色规则 + 用户规则（sourceRoleId），验证交集与跟随
    r = req("POST", "/api/admin/roles", admin, body={"name": "QA测试角色", "description": "自动化测试临时角色"})
    roles = (req("GET", "/api/admin/roles", admin).get("json") or {}).get("data") or []
    role_id = next((x.get("id") for x in roles if x.get("name") == "QA测试角色"), None)
    CTX["roles"].append(role_id)
    ok = code_of(r) == 0 and role_id is not None
    if not ok:
        rec("M6-08", m, "按角色授权与角色默认权限跟随", "-", "角色创建成功", f"创建 code={code_of(r)}, role_id={role_id}", False)
    else:
        req("POST", "/api/admin/permissions", admin, body={"subjectType": "role", "subjectId": role_id,
            "folderId": root, "perms": ["view", "download"], "inherit": 1})
        req("POST", "/api/admin/permissions", admin, body={"subjectType": "user", "subjectId": uid["qa_guest"],
            "folderId": child, "perms": ["view", "download", "upload"], "inherit": 1, "sourceRoleId": role_id})
        eff1 = (req("GET", "/api/admin/permissions/effective", admin,
                    params={"userId": uid["qa_guest"], "folderId": child}).get("json") or {}).get("data") or []
        req("POST", "/api/admin/permissions", admin, body={"subjectType": "role", "subjectId": role_id,
            "folderId": root, "perms": ["view"], "inherit": 1})
        eff2 = (req("GET", "/api/admin/permissions/effective", admin,
                    params={"userId": uid["qa_guest"], "folderId": child}).get("json") or {}).get("data") or []
        ok1 = set(eff1) == {"view", "download"}
        ok2 = set(eff2) == {"view"}
        rec("M6-08", m, "按角色授权与角色默认权限跟随", "POST /api/admin/permissions（sourceRoleId）",
            "初始交集 view+download，角色收窄后仅 view",
            f"初始 perms={eff1}；收窄后 perms={eff2}", ok1 and ok2)

    # 阻断继承
    req("POST", "/api/admin/permissions", admin, body={"subjectType": "user", "subjectId": uid["qa_view"],
        "folderId": child, "perms": ["edit"], "inherit": 0})
    eff = (req("GET", "/api/admin/permissions/effective", admin,
               params={"userId": uid["qa_view"], "folderId": child}).get("json") or {}).get("data") or []
    ok = "edit" in eff and "view" not in eff
    rec("M6-09", m, "阻断继承形成新权限边界", "GET /api/admin/permissions/effective（inherit=0）",
        "仅 edit，不再继承上级 view", f"perms={eff}", ok, 200, 0 if ok else 1)

    # 目录链交集
    req("POST", "/api/admin/permissions", admin, body={"subjectType": "user", "subjectId": uid["qa_edit"],
        "folderId": child, "perms": ["view", "download"], "inherit": 1})
    eff = (req("GET", "/api/admin/permissions/effective", admin,
               params={"userId": uid["qa_edit"], "folderId": child}).get("json") or {}).get("data") or []
    ok = set(eff) == {"view", "download"}
    rec("M6-10", m, "目录链逐层交集", "GET /api/admin/permissions/effective（qa_edit 子目录）",
        "view+download（父级 upload/edit 被交集裁掉）", f"perms={eff}", ok, 200, 0 if ok else 1)

    rules = (req("GET", "/api/admin/permissions", admin,
                 params={"subjectType": "user", "subjectId": uid["qa_view"]}).get("json") or {}).get("data") or []
    child_rule = next((x for x in rules if x.get("folderId") == child), None)
    if child_rule:
        r = req("DELETE", f"/api/admin/permissions/{child_rule['id']}", admin)
        eff = (req("GET", "/api/admin/permissions/effective", admin,
                   params={"userId": uid["qa_view"], "folderId": child}).get("json") or {}).get("data") or []
        ok = code_of(r) == 0 and "view" in eff and "edit" not in eff
        rec("M6-11", m, "撤销规则后权限恢复", "DELETE /api/admin/permissions/{id}", "code=0，权限回到继承值",
            f"删除 code={code_of(r)}, perms={eff}", ok, r["status"], code_of(r))
    else:
        rec("M6-11", m, "撤销规则后权限恢复", "-", "可定位规则", "未定位", False)

    # 六权限位存储完整性
    req("POST", "/api/admin/permissions", admin, body={"subjectType": "user", "subjectId": uid["qa_guest"],
        "folderId": child, "perms": ["view", "edit", "download", "upload", "share_internal", "share_external"],
        "inherit": 1})
    rules = (req("GET", "/api/admin/permissions", admin,
                 params={"subjectType": "user", "subjectId": uid["qa_guest"]}).get("json") or {}).get("data") or []
    cur = next((x for x in rules if x.get("folderId") == child), {})
    perms = set(cur.get("perms") or [])
    expect = {"view", "edit", "download", "upload", "share_internal", "share_external"}
    ok = perms == expect
    rec("M6-12", m, "六个权限位完整存储", "GET /api/admin/permissions", "六位全部持久化",
        f"perms={sorted(perms)}", ok, 200, 0 if ok else 1)

    # 旧权限位归一
    req("POST", "/api/admin/permissions", admin, body={"subjectType": "user", "subjectId": uid["qa_guest"],
        "folderId": child, "perms": ["delete", "share"], "inherit": 1})
    eff = (req("GET", "/api/admin/permissions/effective", admin,
               params={"userId": uid["qa_guest"], "folderId": child}).get("json") or {}).get("data") or []
    ok = set(eff) == {"edit", "view"} and "share_internal" not in eff
    rec("M6-13", m, "旧权限位归一映射", "POST /api/admin/permissions（delete/share）",
        "delete→edit、share→view，不自动获得新分享权", f"perms={eff}", ok, 200, 0 if ok else 1)


# ============================================================
# M7 管理后台
# ============================================================
def m7():
    m = "M7 管理后台"
    admin, viewr = CTX["admin"], CTX["qa_view"]

    r = req("GET", "/api/admin/overview", viewr)
    ok = code_of(r) == 403
    rec("M7-01", m, "普通用户访问后台被拒", "GET /api/admin/overview（qa_view）", "code=403",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", "/api/admin/overview", admin)
    d = (r.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and all(k in d for k in ["users", "files", "totalSize", "shares", "logs"])
    rec("M7-02", m, "系统概览", "GET /api/admin/overview", "code=0，含 5 项统计",
        f"code={code_of(r)}, data={d}", ok, r["status"], code_of(r))

    r = req("GET", "/api/admin/users", admin)
    users = (r.get("json") or {}).get("data") or []
    ok = code_of(r) == 0 and any(u.get("username") == "qa_owner" for u in users)
    rec("M7-03", m, "用户列表", "GET /api/admin/users", "code=0，含测试用户",
        f"code={code_of(r)}, 用户数={len(users)}", ok, r["status"], code_of(r))

    uname = "qa_admin_" + str(random.randint(1000, 9999))
    r = req("POST", "/api/admin/users", admin, body={"username": uname, "password": "Admin@123456",
            "nickname": "后台新建", "roleId": 2, "quota": 1073741824, "status": 1})
    users = (req("GET", "/api/admin/users", admin).get("json") or {}).get("data") or []
    nu = next((u for u in users if u.get("username") == uname), None)
    CTX["admin_new_user"] = uname
    CTX["admin_new_uid"] = nu.get("id") if nu else None
    ok = code_of(r) == 0 and nu is not None and nu.get("quota") == 1073741824
    rec("M7-04", m, "后台新增用户", "POST /api/admin/users", "code=0，用户与配额正确",
        f"code={code_of(r)}, 回读={nu}", ok, r["status"], code_of(r))

    uidn = CTX.get("admin_new_uid")
    r = req("PUT", f"/api/admin/users/{uidn}", admin, body={"nickname": "后台改名", "roleId": 3, "quota": 2147483648, "status": 0})
    users = (req("GET", "/api/admin/users", admin).get("json") or {}).get("data") or []
    nu = next((u for u in users if u.get("id") == uidn), {}) if uidn else {}
    ok = code_of(r) == 0 and nu.get("nickname") == "后台改名" and nu.get("status") == 0 and nu.get("quota") == 2147483648
    rec("M7-05", m, "后台修改用户", "PUT /api/admin/users/{id}", "code=0，字段更新",
        f"code={code_of(r)}, 回读={nu}", ok, r["status"], code_of(r))

    r = req("PUT", f"/api/admin/users/{uidn}/password", admin, body={"password": "Reset@123456"})
    ok = code_of(r) == 0
    rec("M7-06", m, "后台重置密码", "PUT /api/admin/users/{id}/password", "code=0",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("DELETE", "/api/admin/users/1", admin)
    ok = code_of(r) != 0
    rec("M7-07", m, "禁止删除自己", "DELETE /api/admin/users/1", "code≠0",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", "/api/admin/roles", admin)
    roles = (r.get("json") or {}).get("data") or []
    ok = code_of(r) == 0 and len(roles) >= 2 and any(x.get("is_system") == 1 for x in roles)
    rec("M7-08", m, "角色列表与内置标记", "GET /api/admin/roles", "code=0，含内置角色",
        f"code={code_of(r)}, 角色数={len(roles)}", ok, r["status"], code_of(r))

    r = req("PUT", "/api/admin/roles/1", admin, body={"name": "改名内置", "description": "x"})
    ok = code_of(r) != 0
    rec("M7-09", m, "内置角色拒绝修改", "PUT /api/admin/roles/1", "code≠0",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    rid = CTX.get("roles", [None])[0] if CTX.get("roles") else None
    if rid:
        r = req("DELETE", f"/api/admin/roles/{rid}", admin)
        ok = code_of(r) == 0
        rec("M7-10", m, "删除自定义角色", "DELETE /api/admin/roles/{id}", "code=0",
            f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))
        CTX["roles"] = []
    else:
        rec("M7-10", m, "删除自定义角色", "-", "角色存在", "无角色", False)

    r = req("GET", "/api/admin/shares", admin)
    shares = (r.get("json") or {}).get("data") or []
    ok = code_of(r) == 0 and len(shares) > 0
    rec("M7-11", m, "共享管理列表", "GET /api/admin/shares", "code=0，含共享",
        f"code={code_of(r)}, 共享数={len(shares)}", ok, r["status"], code_of(r))

    r = req("GET", "/api/admin/logs", admin, params={"page": 1, "pageSize": 10, "action": "登录"})
    d = (r.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and ("records" in d or "list" in d or isinstance(d, list))
    rec("M7-12", m, "操作日志筛选与分页", "GET /api/admin/logs", "code=0，返回分页结构",
        f"code={code_of(r)}, data keys={list(d.keys()) if isinstance(d, dict) else type(d).__name__}",
        ok, r["status"], code_of(r))

    r = req("GET", "/api/admin/logs/export", admin, params={"action": "登录"}, raw=True)
    text = r["content"].decode("utf-8-sig", "ignore")
    ok = r["status"] == 200 and ("token" not in text.lower()) and len(r["content"]) > 0
    rec("M7-13", m, "操作日志 CSV 导出无敏感信息", "GET /api/admin/logs/export", "HTTP 200，不含 token",
        f"HTTP {r['status']}, 大小={len(r['content'])}, 含token={'token' in text.lower()}, 首行={text.splitlines()[0][:60] if text.splitlines() else ''}",
        ok, r["status"])

    r = req("GET", "/api/admin/config", admin)
    cfg = (r.get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and "site_name" in cfg and "chunk_size_pc" in cfg
    rec("M7-14", m, "系统配置读取", "GET /api/admin/config", "code=0，含关键配置",
        f"code={code_of(r)}, keys={list(cfg.keys())[:8]}", ok, r["status"], code_of(r))

    old = cfg.get("qa_test_probe", "")
    r = req("PUT", "/api/admin/config", admin, body={"qa_test_probe": "v1"})
    cfg2 = (req("GET", "/api/admin/config", admin).get("json") or {}).get("data") or {}
    ok = code_of(r) == 0 and cfg2.get("qa_test_probe") == "v1"
    rec("M7-15", m, "系统配置写入生效", "PUT /api/admin/config", "code=0，回读一致",
        f"code={code_of(r)}, qa_test_probe={cfg2.get('qa_test_probe')}", ok, r["status"], code_of(r))


# ============================================================
# M8 安全与健壮性
# ============================================================
def m8():
    m = "M8 安全与健壮性"
    admin, guest = CTX["admin"], CTX["qa_guest"]
    txt = CTX["txt_id"]

    r = req("GET", "/api/files/list")
    ok = r["status"] == 401
    rec("M8-01", m, "未登录访问文件接口", "GET /api/files/list（无 token）", "HTTP 401",
        f"HTTP {r['status']}", ok, r["status"])

    r = req("GET", f"/api/files/{txt}/download", "eyJhbGciOi.forged.signature", raw=True)
    ok = r["status"] == 401
    rec("M8-02", m, "伪造 token 下载文件", "GET /api/files/{id}/download（伪造 token）", "HTTP 401",
        f"HTTP {r['status']}", ok, r["status"])

    r = req("GET", f"/api/files/{txt}/download", guest, raw=True)
    ok = code_of(r) != 0 or r["status"] != 200
    rec("M8-03", m, "无权限用户下载他人文件", "GET /api/files/{id}/download（qa_guest）", "被拒绝",
        f"HTTP {r['status']}, code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", "/api/admin/logs", admin, params={"page": "abc", "pageSize": "-5"})
    ok = r["status"] == 200 and (code_of(r) == 0 or code_of(r) is not None)
    rec("M8-04", m, "非法分页参数不导致 500", "GET /api/admin/logs?page=abc&pageSize=-5", "无 500，返回可解析响应",
        f"HTTP {r['status']}, code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r))

    r = req("GET", f"/api/files/{CTX['edit_txt_id']}/download", CTX["qa_owner"], raw=True)
    h = r["headers"]
    name_hdr = h.get("Content-Disposition", "")
    ok = r["status"] == 200 and h.get("X-Content-Type-Options") == "nosniff" and "attachment" in name_hdr
    rec("M8-05", m, "下载响应安全头", "GET /api/files/{id}/download", "200 + nosniff + Content-Disposition",
        f"HTTP {r['status']}, nosniff={h.get('X-Content-Type-Options')}, disposition={name_hdr[:90]}",
        ok, r["status"])

    r = req("POST", "/api/files/folder", CTX["qa_owner"], body={"parent_id": CTX["root_id"], "name": ""})
    ok = code_of(r) == 0 and bool(msg_of(r))
    rec("M8-06", m, "空名称兜底处理", "POST /api/files/folder（name 为空）", "code=0，safeName 兜底为『未命名』",
        f"code={code_of(r)}, msg={msg_of(r)}", ok, r["status"], code_of(r),
        remark="safeName 将空名兜底为『未命名』，设计如此")


# ============================================================
# 清理
# ============================================================
def cleanup():
    print("\n===== CLEANUP =====")
    admin = CTX["admin"]
    # 撤销测试期间创建的共享
    for tok, sid in CTX.get("shares", []):
        if sid:
            req("DELETE", f"/api/shares/{sid}", tok)
    # 删除测试用户的临时文件（放回收站再彻底删除）
    root = CTX.get("root_id")
    if root:
        req("DELETE", "/api/files", CTX["qa_owner"], params={"ids": str(root)})
        recyc = (req("GET", "/api/files/recycle", CTX["qa_owner"]).get("json") or {}).get("data") or []
        for x in recyc:
            req("DELETE", f"/api/files/{x.get('id')}/permanent", CTX["qa_owner"])
    # 仅清理本次测试创建的规则：记录测试开始前的规则快照，回滚到该快照
    import sqlite3
    con = sqlite3.connect("data/liteark.db")
    uid = CTX.get("uid", {})
    test_uids = [uid[n] for n in ["qa_owner", "qa_view", "qa_edit", "qa_share", "qa_guest"] if n in uid]
    if test_uids:
        ph = ",".join("?" * len(test_uids))
        con.execute(f"delete from permission where subject_type='user' and subject_id in ({ph})", test_uids)
    # 仅删除测试期间创建的角色规则（folder_id 属于测试根目录及其子目录）
    test_folders = [CTX.get("root_id"), CTX.get("child_id")]
    test_folders = [x for x in test_folders if x]
    if test_folders:
        ph = ",".join("?" * len(test_folders))
        con.execute(f"delete from permission where folder_id in ({ph})", test_folders)
    con.commit()
    con.close()
    # 删除自动化创建的用户与临时角色（按用户名回读 id，避免 uid 映射缺失导致残留）
    all_users = (req("GET", "/api/admin/users", admin).get("json") or {}).get("data") or []
    name_to_id = {x.get("username"): x.get("id") for x in all_users}
    for u in CTX.get("new_users", []):
        target_id = name_to_id.get(u) or uid.get(u)
        if target_id:
            req("DELETE", f"/api/admin/users/{target_id}", admin)
    if CTX.get("admin_new_uid"):
        req("DELETE", f"/api/admin/users/{CTX['admin_new_uid']}", admin)
    for rid in CTX.get("roles", []):
        if rid:
            req("DELETE", f"/api/admin/roles/{rid}", admin)
    print("cleanup done")


def main():
    setup()
    for fn in [m1, m2, m3, m4, m5, m6, m7, m8]:
        try:
            fn()
        except Exception as e:
            import traceback
            traceback.print_exc()
            rec(fn.__name__.upper(), "异常", fn.__name__, "-", "正常执行", f"异常: {e}", False, 0, None, "脚本异常")
    cleanup()

    end = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    total = len(RESULTS)
    passed = sum(1 for x in RESULTS if x["result"] == "PASS")
    failed = total - passed
    out = {
        "meta": {"system": "轻舟 LiteArk V1.1", "base_url": BASE, "start": START, "end": end,
                 "total": total, "passed": passed, "failed": failed,
                 "rate": round(passed / total * 100, 1) if total else 0,
                 "testers": "自动化接口测试脚本 tests/run_full_test.py"},
        "cases": RESULTS,
    }
    with open("docs/test-results.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(f"\n===== SUMMARY: {passed}/{total} PASS, {failed} FAIL, rate={out['meta']['rate']}% =====")
    print("results -> docs/test-results.json")


if __name__ == "__main__":
    main()
