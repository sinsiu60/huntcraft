package kr.huntcraft.client.debug;

/** 디버그 표시 상태 (F7 판정 도형 · F8 정보 오버레이). 세션 중에만 유지. */
public final class DebugState {
	/** 판정 도형 렌더 (Phase 2 에서 연결: 공격 빨강 · 피격 파랑 · 비활성 회색) */
	public static boolean showShapes;
	public static boolean showInfo;

	/** 마지막으로 눌린 전투 입력 (키 동작 확인용) */
	static String lastNote = "-";
	static long lastNoteAt;

	private DebugState() {}

	public static void note(String text) {
		lastNote = text;
		lastNoteAt = System.currentTimeMillis();
	}
}
