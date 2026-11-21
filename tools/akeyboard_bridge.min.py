import ctypes, json, re, select, socket, sys, threading

class G(ctypes.Structure):
    _fields_ = [('a', ctypes.c_uint32), ('b', ctypes.c_uint16), ('c', ctypes.c_uint16), ('d', ctypes.c_ubyte * 8)]
class SB(ctypes.Structure):
    _pack_ = 1
    _fields_ = [('a', ctypes.c_ushort), ('b', ctypes.c_uint64), ('c', G), ('d', ctypes.c_uint32)]
class SA(ctypes.Structure):
    _fields_ = [('p', ctypes.c_void_p), ('l', ctypes.c_int)]
class CA(ctypes.Structure):
    _fields_ = [('a', SA), ('b', SA), ('c', ctypes.c_int), ('d', ctypes.c_int)]
class BL(ctypes.Structure):
    _fields_ = [('s', ctypes.c_uint32), ('p', ctypes.c_void_p)]
class QS(ctypes.Structure):
    _fields_ = [('s', ctypes.c_uint32), ('n', ctypes.c_wchar_p), ('g', ctypes.POINTER(G)), ('v', ctypes.c_void_p), ('c', ctypes.c_wchar_p), ('ns', ctypes.c_uint32), ('p', ctypes.POINTER(G)), ('ctx', ctypes.c_wchar_p), ('np', ctypes.c_uint32), ('pr', ctypes.c_void_p), ('qs', ctypes.c_wchar_p), ('nc', ctypes.c_uint32), ('buf', ctypes.POINTER(CA)), ('f', ctypes.c_uint32), ('b', ctypes.POINTER(BL))]
class MI(ctypes.Structure):
    _fields_ = [('x', ctypes.c_long), ('y', ctypes.c_long), ('d', ctypes.c_uint32), ('f', ctypes.c_uint32), ('t', ctypes.c_uint32), ('e', ctypes.c_size_t)]
class KI(ctypes.Structure):
    _fields_ = [('v', ctypes.c_uint16), ('s', ctypes.c_uint16), ('f', ctypes.c_uint32), ('t', ctypes.c_uint32), ('e', ctypes.c_size_t)]
class HI(ctypes.Structure):
    _fields_ = [('m', ctypes.c_uint32), ('l', ctypes.c_uint16), ('h', ctypes.c_uint16)]
class IU(ctypes.Union):
    _fields_ = [('m', MI), ('k', KI), ('h', HI)]
class IN(ctypes.Structure):
    _fields_ = [('t', ctypes.c_uint32), ('u', IU)]

st = threading.Event()
dn = threading.Event()
P = {10054, 10053, 10058, 10060}
BF = (2, 5, 10)
R1 = re.compile(r"^(?:[ \t]*[\r\n\x0b\x0c\x85\u2028\u2029]+[ \t]*)+|(?:[ \t]*[\r\n\x0b\x0c\x85\u2028\u2029]+[ \t]*)+$")
R2 = re.compile(r"[ \t]*[\r\n\x0b\x0c\x85\u2028\u2029]+[ \t]*")
GD = G(0x8e7b3f20, 0x4c6a, 0x4d8e, (ctypes.c_ubyte * 8)(0x9a, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f, 0x6a, 0x7b))

def _c(t):
    if t in (2, 5, 6): st.set(); dn.wait(3.5); return True
    return False

_HT = ctypes.WINFUNCTYPE(ctypes.c_long, ctypes.c_uint32)
_HD = _HT(_c)

u32 = ctypes.WinDLL("user32", use_last_error=True) if sys.platform == "win32" else None
w2 = ctypes.WinDLL("ws2_32", use_last_error=True) if sys.platform == "win32" else None
k32 = ctypes.WinDLL("kernel32", use_last_error=True) if sys.platform == "win32" else None

if u32:
    u32.SendInput.argtypes = [ctypes.c_uint, ctypes.POINTER(IN), ctypes.c_int]
    u32.SendInput.restype = ctypes.c_uint
    u32.MapVirtualKeyW.argtypes = [ctypes.c_uint, ctypes.c_uint]
    u32.MapVirtualKeyW.restype = ctypes.c_uint
    u32.GetForegroundWindow.argtypes = []
    u32.GetForegroundWindow.restype = ctypes.c_void_p
if w2:
    w2.WSASetServiceW.argtypes = [ctypes.POINTER(QS), ctypes.c_int, ctypes.c_uint32]
    w2.WSASetServiceW.restype = ctypes.c_int
    w2.WSAGetLastError.restype = ctypes.c_int
if k32:
    k32.SetConsoleCtrlHandler.argtypes = [_HT, ctypes.c_long]
    k32.SetConsoleCtrlHandler.restype = ctypes.c_long

def hf(): return bool(u32.GetForegroundWindow()) if u32 else False
def cl(s): return R2.sub(" ", R1.sub("", s))
def mi(v=0, s=0, f=0): return IN(1, IU(k=KI(v, s, f, 0, 0)))

def vk(k, sf=None, mf=None):
    sc = int(mf(k, 0) if mf else u32.MapVirtualKeyW(k, 0))
    arr = (IN * 2)(mi(k, sc, 0), mi(k, sc, 2))
    n = int(sf(2, arr, ctypes.sizeof(IN)) if sf else u32.SendInput(2, arr, ctypes.sizeof(IN)))
    return None if n == 2 else ("uipi" if n == 0 else "send_failed")

def ck(s, m=32):
    b = s.encode("utf-16-le")
    u = [int.from_bytes(b[i:i+2], "little") for i in range(0, len(b), 2)]
    r, i = [], 0
    while i < len(u):
        e = min(i + m, len(u))
        if e < len(u) and 0xD800 <= u[e-1] <= 0xDBFF:
            e = e - 1 if e - i > 1 else e + 1
        r.append(u[i:e]); i = e
    return r

def tx(s, sf=None, m=32):
    c = cl(s)
    if not c: return None
    for p in ck(c, m):
        ev = []
        for u in p: ev.extend([mi(0, u, 4), mi(0, u, 6)])
        arr = (IN * len(ev))(*ev)
        n = int(sf(len(ev), arr, ctypes.sizeof(IN)) if sf else u32.SendInput(len(ev), arr, ctypes.sizeof(IN)))
        if n != len(ev): return "uipi" if n == 0 else "send_failed"
    return None

def pr(l, sf=None, ff=None, mf=None):
    s = l.strip()
    if not s: return None
    try: p = json.loads(s)
    except: return {"ok": False, "err": "bad_json"}
    if not isinstance(p, dict): return {"ok": False, "err": "bad_payload"}
    t = p.get("type")
    f_ok = ff() if ff else hf()
    if t == "text":
        v = p.get("value")
        if not isinstance(v, str): return {"ok": False, "err": "bad_value"}
        if not f_ok: return {"ok": False, "err": "no_focus"}
        e = tx(v, sf=sf)
        return {"ok": True} if e is None else {"ok": False, "err": e}
    if t == "key":
        k = p.get("key")
        vm = {"ENTER": 0x0D, "BACKSPACE": 0x08}
        val = vm.get(k) if isinstance(k, str) else None
        if val is None: return {"ok": False, "err": "bad_key"}
        if not f_ok: return {"ok": False, "err": "no_focus"}
        e = vk(val, sf=sf, mf=mf)
        return {"ok": True} if e is None else {"ok": False, "err": e}
    return {"ok": False, "err": "unknown_type"}

def sdp(pt, op):
    if not w2: return False, -1
    sb = SB(32, 0, GD, pt)
    ca = CA(SA(ctypes.addressof(sb), ctypes.sizeof(sb)), SA(None, 0), socket.SOCK_STREAM, 3)
    qs = QS(ctypes.sizeof(QS), "AKeyboard Voice Bridge", ctypes.pointer(GD), None, "AKeyboard Bluetooth RFCOMM Bridge", 16, None, None, 0, None, None, 1, ctypes.pointer(ca), 0, None)
    if w2.WSASetServiceW(ctypes.byref(qs), op, 0) != 0:
        return False, int(w2.WSAGetLastError())
    return True, 0

class _SE(OSError):
    def __init__(self, w, pt):
        super().__init__(f"SDP:{w}")
        self.winerror = w
        self.port = pt

def cc(c, buf):
    buf.clear()
    try: c.close()
    except: pass

def rj(c, buf):
    print("[WARN:E04]", flush=True)
    try: c.sendall(b'{"ok":false,"err":"line_too_long"}\n')
    finally: cc(c, buf)

def hc(c, buf, pf=pr):
    try:
        d = c.recv(4096)
        if not d:
            print("[DISC]", flush=True); cc(c, buf); return False
        buf.extend(d)
        if len(buf) > 65536 and b"\n" not in buf:
            rj(c, buf); return False
        while True:
            i = buf.find(b"\n")
            if i == -1: break
            raw = bytes(buf[:i])
            del buf[:i+1]
            if len(raw) > 65536:
                rj(c, buf); return False
            try: txt = raw.decode("utf-8")
            except UnicodeDecodeError:
                c.sendall(b'{"ok":false,"err":"utf8"}\n'); continue
            res = pf(txt)
            if res is not None:
                c.sendall((json.dumps(res) + "\n").encode("utf-8"))
        if len(buf) > 65536:
            rj(c, buf); return False
        return True
    except OSError as e:
        cc(c, buf)
        if isinstance(e, (ConnectionResetError, ConnectionAbortedError, BrokenPipeError)) or getattr(e, "winerror", None) in P:
            print("[DISC]", flush=True); return False
        raise

def run(se=None, cd=None):
    for s in (sys.stdout, sys.stderr):
        if hasattr(s, "reconfigure"):
            try: s.reconfigure(encoding="utf-8", errors="replace")
            except: pass
    if sys.platform != "win32" or sys.version_info < (3, 10):
        print("[E01] See tools/ERROR_CODES.md", file=sys.stderr); sys.exit(1)
    if not hasattr(socket, "AF_BLUETOOTH") or not hasattr(socket, "BTPROTO_RFCOMM"):
        print("[E02] See tools/ERROR_CODES.md", file=sys.stderr); sys.exit(1)
    ev_stop = se or st
    ev_done = cd or dn
    ev_stop.clear(); ev_done.clear()
    hr = False
    if k32 and _HD: hr = bool(k32.SetConsoleCtrlHandler(_HD, 1))
    print("[START] AKeyboard Bridge (min) - See tools/ERROR_CODES.md", flush=True)
    bi, le = 0, None
    try:
        while not ev_stop.is_set():
            ss, cs, sdp_ok, pt, rd, ex = None, None, False, 0, None, False
            try:
                ss = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
                ss.bind(("00:00:00:00:00:00", 0xFFFFFFFF))
                ss.listen(1)
                pt = int(ss.getsockname()[1])
                if pt <= 0: raise OSError("[E03]")
                sdp_ok, wsa_err = sdp(pt, 0)
                if not sdp_ok: raise _SE(wsa_err, pt)
                print(f"[READY:ch={pt}]", flush=True)
                bi, le = 0, None
                buf = bytearray()
                while not ev_stop.is_set():
                    w = [ss] + ([cs] if cs else [])
                    r, _, _ = select.select(w, [], [], 0.5)
                    if ev_stop.is_set(): break
                    ac = False
                    if ss in r:
                        ns, _ = ss.accept()
                        if cs: cc(cs, buf)
                        else: buf.clear()
                        cs, ac = ns, True
                        print("[CONN]", flush=True)
                    if not ac and cs and cs in r:
                        if not hc(cs, buf): cs = None
            except KeyboardInterrupt:
                ex = True
            except OSError as e:
                if ev_stop.is_set(): ex = True
                else:
                    rd = BF[bi]
                    bi = min(bi + 1, len(BF) - 1)
                    we = getattr(e, "winerror", None)
                    tag = f"[SDP:{e.winerror}]" if isinstance(e, _SE) else (f"[W{we}]" if we else (str(e) if str(e) == "[E03]" else f"[W:{e}]"))
                    if tag != le:
                        print(f"{tag} Retry in {rd}s (See tools/ERROR_CODES.md)", flush=True)
                        le = tag
            finally:
                if cs:
                    try: cs.close()
                    except: pass
                    cs = None
                if sdp_ok and pt > 0:
                    sdp(pt, 2); sdp_ok = False
                if ss:
                    try: ss.close()
                    except: pass
                    ss = None
            if ex or ev_stop.is_set(): break
            if rd is not None:
                try:
                    if ev_stop.wait(rd): break
                except KeyboardInterrupt:
                    break
    finally:
        ev_done.set()
        if hr and k32 and _HD: k32.SetConsoleCtrlHandler(_HD, 0)

if __name__ == "__main__":
    run()
