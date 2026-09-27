package kr.huntcraft.anim;

import java.util.Locale;
import java.util.function.DoubleUnaryOperator;

/**
 * GeckoLib 키프레임 이징 (Blockbench GeckoLib 플러그인의 "easing" · "easingArgs") — GeckoLib 5 EasingType 과 같은 식.
 * 키프레임의 이징은 "앞 키프레임 → 이 키프레임" 구간에 쓰임 (GeckoLib KeyframeTriplet 규칙).
 *
 *   in = 함수 그대로, out = 1 - f(1 - t), inOut = 앞 절반 f(2t)/2 · 뒤 절반 1 - f(2(1-t))/2
 *   sine 1 - cos(tπ/2) · quad t² · cubic t³ · quart t⁴ · quint t⁵ · expo 2^(10(t-1)) · circ 1 - √(1 - t²)
 *   back(a) t²((b+1)t - b), b = 1.70158·a (a 기본 1) · elastic(a) 1 - cos(tπ/2)³·cos(t·a·π) (a 기본 1)
 *   bounce(a) 네 포물선의 최솟값 (a 기본 0.5)
 */
public final class Easing {
	private Easing() {}

	/** 이징 이름 (대소문자 무시) → 0..1 변환. 모르는 이름 · linear 는 null (직선) */
	public static DoubleUnaryOperator of(String name, Double arg) {
		if (name == null) {
			return null;
		}
		String n = name.toLowerCase(Locale.ROOT);
		String kind;
		String shape;
		if (n.startsWith("easeinout")) {
			kind = "inout";
			shape = n.substring(9);
		} else if (n.startsWith("easein")) {
			kind = "in";
			shape = n.substring(6);
		} else if (n.startsWith("easeout")) {
			kind = "out";
			shape = n.substring(7);
		} else {
			return n.equals("step") ? t -> t >= 1.0 ? 1.0 : 0.0 : null;
		}
		DoubleUnaryOperator f = switch (shape) {
			case "sine" -> t -> 1.0 - Math.cos(t * Math.PI / 2.0);
			case "quad" -> t -> t * t;
			case "cubic" -> t -> t * t * t;
			case "quart" -> t -> Math.pow(t, 4.0);
			case "quint" -> t -> Math.pow(t, 5.0);
			case "expo" -> t -> Math.pow(2.0, 10.0 * (t - 1.0));
			case "circ" -> t -> 1.0 - Math.sqrt(1.0 - t * t);
			case "back" -> back(arg);
			case "elastic" -> elastic(arg);
			case "bounce" -> bounce(arg);
			default -> null;
		};
		if (f == null) {
			return null;
		}
		return switch (kind) {
			case "out" -> t -> 1.0 - f.applyAsDouble(1.0 - t);
			case "inout" -> t -> t < 0.5 ? f.applyAsDouble(t * 2.0) / 2.0 : 1.0 - f.applyAsDouble((1.0 - t) * 2.0) / 2.0;
			default -> f;
		};
	}

	private static DoubleUnaryOperator back(Double arg) {
		double b = arg == null ? 1.70158 : arg * 1.70158;
		return t -> t * t * ((b + 1.0) * t - b);
	}

	private static DoubleUnaryOperator elastic(Double arg) {
		double b = arg == null ? 1.0 : arg;
		return t -> 1.0 - Math.pow(Math.cos(t * Math.PI / 2.0), 3.0) * Math.cos(t * b * Math.PI);
	}

	private static DoubleUnaryOperator bounce(Double arg) {
		double b = arg == null ? 0.5 : arg;
		return t -> {
			double f0 = 7.5625 * t * t;
			double f1 = 30.25 * b * Math.pow(t - 0.5454545617103577, 2.0) + 1.0 - b;
			double f2 = 121.0 * b * b * Math.pow(t - 0.8181818127632141, 2.0) + 1.0 - b * b;
			double f3 = 484.0 * b * b * b * Math.pow(t - 0.9545454382896423, 2.0) + 1.0 - b * b * b;
			return Math.min(Math.min(f0, f1), Math.min(f2, f3));
		};
	}
}
