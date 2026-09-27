package kr.huntcraft.collision;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.JsonParser;
import kr.huntcraft.HuntCraft;
import kr.huntcraft.config.ConfigIO;

/**
 * 판정 데이터 (스켈레톤 · 판정 도형) — 코드 수정 없이 튜닝.
 *
 *   config/huntcraft/defs/skeletons/<id>.geo.json   Blockbench 에서 내보낸 모델 (본만 사용)
 *   config/huntcraft/defs/colliders/<id>.json       판정 도형 ({@link ColliderDef}) · 부위 육질
 *   config/huntcraft/defs/animations/*.animation.json  Blockbench 애니메이션 (서버 판정 · 클라이언트 렌더 공용)
 *   config/huntcraft/defs/motions/<무기>.json        모션 데이터 ({@link kr.huntcraft.combat.MoveSet})
 *   config/huntcraft/defs/monsters/<몬스터>.json     몬스터 능력치 · 행동 ({@link kr.huntcraft.monster.MonsterDef})
 *
 * 처음 실행하면 모드에 들어 있는 기본 파일을 복사하고 (이미 있는 파일은 덮어쓰지 않음), 서버가 읽은 원문을
 * 접속한 클라이언트에 그대로 보내 같은 정의로 디버그 렌더를 합니다. /huntcraft reload 로 다시 읽음.
 */
public final class Defs {
	/**
	 * 모드에 들어 있는 기본 파일 (resources/huntcraft/defs/ 아래 경로) — 빌드가 만든 index.txt 에서 읽음.
	 * 보스 · 무기를 더할 때 파일만 넣으면 되고 여기 목록을 고칠 필요 없음.
	 */
	private static final String[] BUNDLED = bundledIndex();

	private static String[] bundledIndex() {
		try (InputStream in = Defs.class.getResourceAsStream("/huntcraft/defs/index.txt")) {
			if (in == null) {
				HuntCraft.LOGGER.error("huntcraft/defs/index.txt 가 없음 — 빌드의 generateDefsIndex 확인");
				return new String[0];
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().map(String::trim).filter(l -> !l.isEmpty())
					.toArray(String[]::new);
		} catch (IOException e) {
			HuntCraft.LOGGER.error("defs 목록 읽기 실패: {}", e.getMessage());
			return new String[0];
		}
	}

	/** 이번 실행의 백업 폴더 이름 */
	private static final String BACKUP_STAMP = java.time.LocalDateTime.now()
			.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

	private static Map<String, Skeleton> skeletons = Map.of();
	private static Map<String, ColliderDef> colliders = Map.of();
	private static Map<String, kr.huntcraft.anim.Animation> animations = Map.of();
	private static Map<String, kr.huntcraft.combat.MoveSet> moveSets = Map.of();
	private static Map<String, kr.huntcraft.monster.MonsterDef> monsters = Map.of();
	/** 경로 → 원문 (클라이언트 동기화용) */
	private static Map<String, String> sources = Map.of();

	private Defs() {}

	public static ColliderDef collider(String id) {
		return colliders.get(id);
	}

	public static kr.huntcraft.anim.Animation animation(String name) {
		return name == null ? null : animations.get(name);
	}

	public static kr.huntcraft.monster.MonsterDef monster(String id) {
		return monsters.get(id);
	}

	public static java.util.Set<String> monsterIds() {
		return monsters.keySet();
	}

	public static kr.huntcraft.combat.MoveSet moveSet(String id) {
		return moveSets.get(id);
	}

	public static Map<String, String> sources() {
		return sources;
	}

	public static Path dir() {
		return ConfigIO.dir().resolve("defs");
	}

	/** 서버: 파일에서 */
	public static void load() {
		exportDefaults();
		Map<String, String> files = new TreeMap<>();
		for (String sub : new String[] {"skeletons", "colliders", "animations", "motions", "monsters"}) {
			Path d = dir().resolve(sub);
			if (!Files.isDirectory(d)) {
				continue;
			}
			try (Stream<Path> list = Files.list(d)) {
				for (Path p : (Iterable<Path>) list.filter(f -> f.toString().endsWith(".json"))::iterator) {
					files.put(sub + "/" + p.getFileName(), Files.readString(p, StandardCharsets.UTF_8));
				}
			} catch (IOException e) {
				HuntCraft.LOGGER.error("판정 데이터 폴더를 읽지 못함 {}: {}", d, e.getMessage());
			}
		}
		apply(files);
	}

	/** 클라이언트: 서버가 보내 준 원문에서 */
	public static void apply(Map<String, String> files) {
		Map<String, Skeleton> sk = new HashMap<>();
		Map<String, ColliderDef> col = new HashMap<>();
		for (Map.Entry<String, String> f : files.entrySet()) {
			String path = f.getKey();
			if (!path.startsWith("skeletons/")) {
				continue;
			}
			String id = name(path).replace(".geo.json", "").replace(".json", "");
			try {
				sk.put(id, Skeleton.parseGeo(id, JsonParser.parseString(f.getValue()).getAsJsonObject()));
			} catch (RuntimeException e) {
				HuntCraft.LOGGER.error("스켈레톤 {} 오류: {}", path, e.getMessage());
			}
		}
		for (Map.Entry<String, String> f : files.entrySet()) {
			String path = f.getKey();
			if (!path.startsWith("colliders/")) {
				continue;
			}
			String id = name(path).replace(".json", "");
			try {
				col.put(id, ColliderDef.parse(id, JsonParser.parseString(f.getValue()).getAsJsonObject(), sk::get));
			} catch (RuntimeException e) {
				HuntCraft.LOGGER.error("판정 도형 {} 오류: {}", path, e.getMessage());
			}
		}
		Map<String, kr.huntcraft.anim.Animation> anim = new HashMap<>();
		Map<String, kr.huntcraft.combat.MoveSet> moves = new HashMap<>();
		Map<String, kr.huntcraft.monster.MonsterDef> mons = new HashMap<>();
		for (Map.Entry<String, String> f : files.entrySet()) {
			String path = f.getKey();
			try {
				if (path.startsWith("animations/")) {
					anim.putAll(kr.huntcraft.anim.Animation.parseFile(JsonParser.parseString(f.getValue()).getAsJsonObject()));
				} else if (path.startsWith("monsters/")) {
					String id = name(path).replace(".json", "");
					mons.put(id, kr.huntcraft.monster.MonsterDef.parse(id, JsonParser.parseString(f.getValue()).getAsJsonObject()));
				} else if (path.startsWith("motions/")) {
					String id = name(path).replace(".json", "");
					moves.put(id, kr.huntcraft.combat.MoveSet.parse(id, JsonParser.parseString(f.getValue()).getAsJsonObject()));
				}
			} catch (RuntimeException e) {
				HuntCraft.LOGGER.error("{} 오류: {}", path, e.getMessage());
			}
		}
		for (kr.huntcraft.monster.MonsterDef md : mons.values()) {
			if (!col.containsKey(md.collider)) {
				HuntCraft.LOGGER.error("몬스터 {} 의 판정 도형 '{}' 이 없음", md.id, md.collider);
			}
			for (kr.huntcraft.monster.MonsterDef.Action a : md.actions.values()) {
				if (!anim.containsKey(a.animation())) {
					HuntCraft.LOGGER.error("몬스터 {}.{} 의 애니메이션 '{}' 이 없음", md.id, a.id(), a.animation());
				}
			}
		}
		// 모션이 가리키는 애니메이션이 있는지
		for (kr.huntcraft.combat.MoveSet set : moves.values()) {
			for (kr.huntcraft.combat.MoveSet.Motion m : set.motions.values()) {
				if (m.animation() != null && !anim.containsKey(m.animation())) {
					HuntCraft.LOGGER.error("모션 {}.{} 의 애니메이션 '{}' 이 없음", set.id, m.id(), m.animation());
				}
			}
		}
		skeletons = sk;
		colliders = col;
		animations = anim;
		moveSets = moves;
		monsters = mons;
		sources = new LinkedHashMap<>(files);
		HuntCraft.LOGGER.info("판정 데이터: 스켈레톤 {}개 · 판정 도형 {}개 · 애니메이션 {}개 · 무기 모션 {}개 · 몬스터 {}개",
				sk.size(), col.size(), anim.size(), moves.size(), mons.size());
	}

	private static String name(String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	/**
	 * 기본 파일 내보내기 (내보낸 내용의 해시는 defs/.bundled.json 에 기록).
	 *   지난번 내보낸 그대로(손대지 않음) + 기본값이 바뀜 → 새 기본값으로 교체
	 *   내보낸 뒤 사용자가 고침 → 그대로 둠
	 *   기록이 없는데 기본값과 다름 (기록 기능 이전 파일) → defs/_backup/<시각>/ 에 백업하고 새 기본값으로
	 */
	private static void exportDefaults() {
		Path record = dir().resolve(".bundled.json");
		com.google.gson.JsonObject hashes = new com.google.gson.JsonObject();
		try {
			if (Files.isRegularFile(record)) {
				hashes = JsonParser.parseString(Files.readString(record, StandardCharsets.UTF_8)).getAsJsonObject();
			}
		} catch (IOException | RuntimeException e) {
			HuntCraft.LOGGER.warn("defs/.bundled.json 을 읽지 못함 — 새로 만듦");
		}
		boolean dirty = false;
		for (String rel : BUNDLED) {
			Path target = dir().resolve(rel);
			byte[] bundled;
			try (InputStream in = Defs.class.getResourceAsStream("/huntcraft/defs/" + rel)) {
				if (in == null) {
					HuntCraft.LOGGER.error("모드에 기본 판정 파일이 없음: {}", rel);
					continue;
				}
				bundled = in.readAllBytes();
			} catch (IOException e) {
				HuntCraft.LOGGER.error("기본 판정 파일 읽기 실패 {}: {}", rel, e.getMessage());
				continue;
			}
			String bundledHash = hash(bundled);
			try {
				if (Files.exists(target)) {
					String current = hash(Files.readAllBytes(target));
					String exported = hashes.has(rel) ? hashes.get(rel).getAsString() : null;
					if (current.equals(bundledHash)) {
						// 이미 최신
						if (!bundledHash.equals(exported)) {
							hashes.addProperty(rel, bundledHash);
							dirty = true;
						}
						continue;
					}
					if (exported != null && !current.equals(exported)) {
						// 내보낸 뒤 사용자가 고친 파일 — 그대로 둠
						HuntCraft.LOGGER.info("판정 파일 {} 은 고친 파일이라 새 기본값으로 바꾸지 않음 (새 기본값은 모드 jar 안 huntcraft/defs/{})", rel, rel);
						continue;
					}
					if (exported == null) {
						// 기록이 없음 (기록 기능 이전에 내보낸 파일) — 고쳤는지 알 수 없으니 백업하고 새 기본값으로
						Path backup = dir().resolve("_backup").resolve(BACKUP_STAMP).resolve(rel);
						Files.createDirectories(backup.getParent());
						Files.copy(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
						HuntCraft.LOGGER.warn("판정 파일 {} 을 새 기본값으로 바꿈 — 이전 파일은 {} 에 백업", rel, backup);
					} else {
						HuntCraft.LOGGER.info("기본 판정 파일 갱신 (손대지 않은 파일): {}", rel);
					}
				}
				Files.createDirectories(target.getParent());
				Files.write(target, bundled);
				hashes.addProperty(rel, bundledHash);
				dirty = true;
			} catch (IOException e) {
				HuntCraft.LOGGER.error("기본 판정 파일 복사 실패 {}: {}", rel, e.getMessage());
			}
		}
		// 모드에서 빠진 기본 파일 (예: 지운 보스): 손대지 않았으면 백업 폴더로 옮기고, 고친 파일이면 그대로 둠
		java.util.Set<String> bundledSet = java.util.Set.of(BUNDLED);
		for (String rel : new java.util.ArrayList<>(hashes.keySet())) {
			if (bundledSet.contains(rel)) {
				continue;
			}
			Path target = dir().resolve(rel);
			try {
				if (Files.exists(target) && !hash(Files.readAllBytes(target)).equals(hashes.get(rel).getAsString())) {
					HuntCraft.LOGGER.info("모드에서 빠진 판정 파일 {} 은 고친 파일이라 그대로 둠", rel);
					continue;
				}
				if (Files.exists(target)) {
					Path backup = dir().resolve("_backup").resolve(BACKUP_STAMP).resolve(rel);
					Files.createDirectories(backup.getParent());
					Files.move(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					HuntCraft.LOGGER.info("모드에서 빠진 판정 파일 {} 을 치움 — {} 에 보관", rel, backup);
				}
				hashes.remove(rel);
				dirty = true;
			} catch (IOException e) {
				HuntCraft.LOGGER.error("빠진 판정 파일 정리 실패 {}: {}", rel, e.getMessage());
			}
		}
		if (dirty) {
			try {
				Files.createDirectories(record.getParent());
				Files.writeString(record, hashes.toString(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				HuntCraft.LOGGER.error("defs/.bundled.json 저장 실패: {}", e.getMessage());
			}
		}
	}

	private static String hash(byte[] data) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
