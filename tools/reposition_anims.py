"""
재배치 패턴 애니메이션 만들기 (iron_golem.animation.json 에 추가 · 덮어씀).

기존 도약 (leap) 애니메이션의 자세를 뽑아 이어 붙여서 만듦 — 새 자세를 손으로 잡지 않아 모델 비율 · 관절 방향이 기존 동작과 맞음.
  backstep          뒤로 크게 도약: 웅크림 → 박차고 뒤로 → 공중 → 착지 (무릎 굽힘) → 대기 자세
  side_step_right   옆으로 짧게 도약 (오른쪽) — side_step_left 는 좌우 대칭
  leap_high         높이 도약 → 내려찍기: leap 의 시간만 늘림 (공중에 오래 떠 있음)

    python3 tools/reposition_anims.py
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PATH = os.path.join(HERE, "..", "src", "main", "resources", "huntcraft", "defs", "animations", "iron_golem.animation.json")
BONES = ["body", "head", "right_arm", "right_arm2", "left_arm", "left_arm2", "right_leg", "left_leg"]
FPS = 60.0


def load():
    with open(PATH, encoding="utf-8") as f:
        return json.load(f)


def save(doc):
    with open(PATH, "w", encoding="utf-8") as f:
        f.write(json.dumps(doc, indent="\t", ensure_ascii=False) + "\n")


def channels(anims, name):
    """애니메이션의 (본, 채널) → [(초, 값)] (구운 키 — 프레임마다)"""
    out = {}
    for bone, chs in anims[name]["bones"].items():
        for ch, keys in chs.items():
            pts = []
            for t, v in keys.items():
                val = v.get("post", v.get("pre")) if isinstance(v, dict) else v
                pts.append((float(t), val))
            pts.sort()
            out[(bone, ch)] = pts
    return out


def sample(chs, seconds):
    """seconds 시점 자세 (키 사이는 선형 — 구운 키가 촘촘해서 충분)"""
    pose = {}
    for key, pts in chs.items():
        if seconds <= pts[0][0]:
            pose[key] = list(pts[0][1])
            continue
        if seconds >= pts[-1][0]:
            pose[key] = list(pts[-1][1])
            continue
        for (t0, v0), (t1, v1) in zip(pts, pts[1:]):
            if t0 <= seconds <= t1:
                f = (seconds - t0) / max(1e-9, t1 - t0)
                pose[key] = [a + (b - a) * f for a, b in zip(v0, v1)]
                break
    return pose


def lerp(p, q, f):
    keys = set(p) | set(q)
    return {k: [a + (b - a) * f for a, b in zip(p.get(k, [0, 0, 0]), q.get(k, [0, 0, 0]))] for k in keys}


def put(pose, **changes):
    """put(pose, body_rotation=[x, None, None]) — None 은 그대로"""
    out = {k: list(v) for k, v in pose.items()}
    for name, vec in changes.items():
        bone, ch = name.rsplit("_", 1)
        cur = out.get((bone, ch), [0.0, 0.0, 0.0])
        out[(bone, ch)] = [c if v is None else v for c, v in zip(cur, vec)]
    return out


def mirror(pose):
    """좌우 대칭: 오른쪽 ↔ 왼쪽 본, 회전 [x, -y, -z] · 위치 [-x, y, z]"""
    out = {}
    for (bone, ch), v in pose.items():
        b = bone.replace("right_", "@").replace("left_", "right_").replace("@", "left_")
        out[(b, ch)] = [v[0], -v[1], -v[2]] if ch == "rotation" else [-v[0], v[1], v[2]]
    return out


def keyed(frames, length, loop=False):
    """[(프레임, 자세)] → Bedrock 애니메이션 (부드러운 키)"""
    bones = {}
    for frame, pose in frames:
        t = "%g" % round(frame / FPS, 4)
        if "." not in t:
            t += ".0"
        for (bone, ch), v in sorted(pose.items()):
            if bone not in BONES:
                continue
            bones.setdefault(bone, {}).setdefault(ch, {})[t] = {"post": [round(x, 3) for x in v], "lerp_mode": "catmullrom"}
    anim = {"animation_length": round(length / FPS, 4), "bones": {b: bones[b] for b in BONES if b in bones}}
    if loop:
        anim = {"loop": True, **anim}
    return anim


def warped(chs, length, table, every=2):
    """시간 늘이기: table = [(새 프레임, 원래 프레임)] 사이 선형 — every 프레임마다 굽기"""
    frames = []
    for f in list(range(0, length, every)) + [length]:
        for (n0, o0), (n1, o1) in zip(table, table[1:]):
            if n0 <= f <= n1:
                src = o0 + (o1 - o0) * (f - n0) / max(1e-9, n1 - n0)
                break
        else:
            src = table[-1][1]
        frames.append((f, sample(chs, src / FPS)))
    return keyed(frames, length)


def main():
    doc = load()
    anims = doc["animations"]
    anims.pop("body_crush", None)
    leap = channels(anims, "leap")
    # 대기 자세에는 다리 채널이 없음 → 0 (쉬는 자세) 으로 채워야 첫 키부터 다리가 굽어 있지 않음
    idle = {**{k: [0.0, 0.0, 0.0] for k in leap}, **sample(channels(anims, "idle"), 0.0)}
    crouch = sample(leap, 0.55)
    launch = sample(leap, 0.72)
    air = sample(leap, 1.0)
    land = sample(leap, 1.42)

    # ── 백스텝: 몸을 뒤로 젖히며 박참 (팔은 앞으로 뻗어 균형) ──
    b_crouch = put(lerp(idle, crouch, 0.75), body_rotation=[8, None, None], head_rotation=[0, None, None])
    b_push = put(launch, body_rotation=[-14, None, None], head_rotation=[-10, None, None],
                 right_arm_rotation=[-75, None, None], left_arm_rotation=[-75, None, None])
    b_air = put(air, body_rotation=[-8, None, None], right_arm_rotation=[-60, None, None], left_arm_rotation=[-60, None, None],
                right_leg_rotation=[-30, None, None], left_leg_rotation=[-30, None, None])
    b_pre = put(lerp(b_air, idle, 0.4), right_leg_rotation=[-20, None, None], left_leg_rotation=[-20, None, None])
    b_land = put(lerp(idle, land, 0.6), body_rotation=[10, None, None],
                 right_arm_rotation=[-25, None, None], left_arm_rotation=[-25, None, None])
    anims["backstep"] = keyed([(0, idle), (12, b_crouch), (18, b_push), (32, b_air), (46, b_pre), (51, b_land),
                               (64, lerp(b_land, idle, 0.6)), (84, idle)], 84)

    # ── 옆 걸음 (오른쪽): 가볍게 웅크렸다 옆으로 튐 ──
    s_crouch = lerp(idle, crouch, 0.5)
    s_push = lerp(idle, launch, 0.35)
    s_air = lerp(idle, air, 0.3)
    s_land = lerp(idle, land, 0.45)
    right = [(0, idle), (8, s_crouch), (11, s_push), (20, s_air), (31, s_land), (50, lerp(s_land, idle, 0.6)), (80, idle)]
    anims["side_step_right"] = keyed(right, 80)
    anims["side_step_left"] = keyed([(f, mirror(p)) for f, p in right], 80)

    # ── 높이 도약: 도약 (leap) 의 공중 구간을 늘림 — 새 프레임 → 원래 프레임 ──
    anims["leap_high"] = warped(leap, 215, [(0, 0), (45, 45), (60, 58), (112, 75), (126, 87), (215, 176)])
    save(doc)
    print("backstep · side_step_right · side_step_left · leap_high 저장, body_crush 삭제")


if __name__ == "__main__":
    main()
