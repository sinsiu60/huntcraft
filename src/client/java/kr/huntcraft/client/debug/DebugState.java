package kr.huntcraft.client.debug;

import java.util.ArrayDeque;
import java.util.Deque;

import kr.huntcraft.combat.CombatInput;

/** 디버그 표시 상태 (F7 판정 도형 · F8 정보 오버레이). 세션 중에만 유지. */
public final class DebugState {
	/** 판정 도형 렌더 (Phase 2 에서 연결: 공격 빨강 · 피격 파랑 · 비활성 회색) */
	public static boolean showShapes;
	public static boolean showInfo;

	/** 해석된 입력 기록 (최근 순) — 조합 판정 · 입력 간격(프레임) 확인용 */
	record Entry(String text, long tick) {}

	static final int HISTORY = 8;
	static final Deque<Entry> history = new ArrayDeque<>();
	/** 클라이언트 틱 번호 (60Hz) */
	static long clientTick;

	private DebugState() {}

	public static void input(CombatInput input) {
		push(input.label + " [" + input.source + "]");
	}

	public static void note(String text) {
		push(text);
	}

	private static void push(String text) {
		history.addFirst(new Entry(text, clientTick));
		while (history.size() > HISTORY) {
			history.removeLast();
		}
	}
}
