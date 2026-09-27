package kr.huntcraft.test;

import java.util.Arrays;

/**
 * 시험 골라 돌리기 — ./gradlew runClientGameTest -Ponly=Phase3ClientTest,MotionGalleryClientTest
 * 지정이 없으면 일반 시험 전부 (모션 갤러리처럼 opt-in 인 것은 이름을 적었을 때만).
 */
final class TestFilter {
	private TestFilter() {}

	static boolean skip(Class<?> test, boolean optIn) {
		String only = System.getProperty("huntcraft.only", "");
		if (only.isBlank()) {
			return optIn;
		}
		return Arrays.stream(only.split(",")).map(String::trim).noneMatch(test.getSimpleName()::equals);
	}
}
