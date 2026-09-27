package kr.huntcraft.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import io.netty.buffer.ByteBuf;
import kr.huntcraft.HuntCraft;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 서버 → 클라이언트: 판정 데이터 원문 (경로 → JSON). 클라이언트는 같은 파서로 읽어 디버그 렌더에 씀.
 * gzip 으로 묶어 보냄 — 촘촘한 몬스터 애니메이션은 원문이 1MB 를 넘어 문자열 한도 · 커스텀 패킷 한도(1MiB)에 걸림.
 * 압축 후에도 한도를 넘으면 인코딩에서 예외 (파일을 줄여야 함).
 */
public record DefsSyncPayload(Map<String, String> files) implements CustomPacketPayload {
	public static final Type<DefsSyncPayload> TYPE = new Type<>(HuntCraft.id("defs_sync"));
	/** 커스텀 패킷 한도보다 조금 작게 */
	private static final int MAX_BYTES = 1_000_000;
	public static final StreamCodec<ByteBuf, DefsSyncPayload> CODEC = StreamCodec.of(
			(buf, msg) -> {
				byte[] data = pack(msg.files);
				if (data.length > MAX_BYTES) {
					throw new IllegalStateException("판정 데이터가 압축 후에도 너무 큼: " + data.length + " 바이트");
				}
				buf.writeInt(data.length);
				buf.writeBytes(data);
			},
			buf -> {
				int n = buf.readInt();
				if (n < 0 || n > MAX_BYTES) {
					throw new IllegalStateException("판정 데이터 크기가 이상함: " + n);
				}
				byte[] data = new byte[n];
				buf.readBytes(data);
				return new DefsSyncPayload(unpack(data));
			});

	private static byte[] pack(Map<String, String> files) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
			out.writeInt(files.size());
			for (Map.Entry<String, String> e : files.entrySet()) {
				writeString(out, e.getKey());
				writeString(out, e.getValue());
			}
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return bytes.toByteArray();
	}

	private static Map<String, String> unpack(byte[] data) {
		Map<String, String> files = new HashMap<>();
		try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
			int count = in.readInt();
			for (int i = 0; i < count; i++) {
				files.put(readString(in), readString(in));
			}
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return files;
	}

	/** writeUTF 는 64KB 한도라 길이 + UTF-8 바이트로 */
	private static void writeString(DataOutputStream out, String s) throws IOException {
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		out.writeInt(b.length);
		out.write(b);
	}

	private static String readString(DataInputStream in) throws IOException {
		int n = in.readInt();
		if (n < 0 || n > 64 << 20) {
			throw new IOException("문자열 길이가 이상함: " + n);
		}
		byte[] b = new byte[n];
		in.readFully(b);
		return new String(b, StandardCharsets.UTF_8);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
